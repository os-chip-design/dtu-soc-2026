import chisel3._
import chisel3.util._

// Multi-layer SRAM-backed SNN with runtime-loaded layer metadata.
// Option A: supports any neuron count per layer using sub-group processing,
// within the compile-time capacity envelope implied by `capacityLayerSizes`.
//
// `capacityLayerSizes` does not describe the exact runtime network in flex mode.
// Instead it sizes the generated hardware for the worst case: number of layers,
// spike-vector width, metadata table sizes, counter widths, and SRAM capacity.
// The actual network shape is loaded from the SRAM header at start.
//
// 4 accumulators process groups of 4 output neurons at a time.
// For a layer with nOut neurons, we need ceil(nOut/4) "sub-groups".
// Each sub-group requires nIn SRAM reads (one per input).
//
// SRAM layout in flex mode:
//   word 0      = nLayers
//   word 1..N+1 = layer sizes (input layer first)
//   remaining words hold packed weights row-major by sub-group:
//     Layer k, input i, sub-group g → address = base_k + i * nGroups_k + g
//   Each 32-bit word holds 4 weights: [w3|w2|w1|w0] for neurons 4g..4g+3
//
// The header is read once at start and cached into small register tables so
// inference can use the single SRAM port exclusively for weight fetches.
class MultiLayerSnnFlex(
  capacityLayerSizes: Seq[Int],
  weightWidth: Int = 8,
  neuronWidth: Int = 16,
  threshold: Int = 64,
  leak: Int = 1
) extends Module {
  require(capacityLayerSizes.length >= 2, "Need at least input and output layer")
  require(weightWidth * 4 <= 32, "4 weights must fit in 32-bit SRAM word")

  val maxSupportedLayers = capacityLayerSizes.length - 1
  val maxInputsPerLayer  = capacityLayerSizes.init.max
  val maxOutputsPerLayer = capacityLayerSizes.tail.max
  val inputVectorWidth   = capacityLayerSizes.head
  val outputVectorWidth  = capacityLayerSizes.last
  val maxLayerWidth      = capacityLayerSizes.max

  val maxOutputGroups = capacityLayerSizes.tail.map(n => (n + 3) / 4).max
  val configHeaderWords = maxSupportedLayers + 2
  val maxWeightWords = capacityLayerSizes.init.zip(capacityLayerSizes.tail).map { case (nIn, nOut) =>
    nIn * ((nOut + 3) / 4)
  }.sum
  val totalSramWords = configHeaderWords + maxWeightWords
  require(totalSramWords <= 256, s"Total SRAM usage ($totalSramWords words) exceeds 256")

  val sramDataWidth = 32
  val sramAddrWidth = 8
  val layerCountWidth = log2Ceil(maxSupportedLayers + 1).max(1)
  val layerSizeWidth  = log2Ceil(maxLayerWidth + 1).max(1)
  val groupWidth      = log2Ceil(maxOutputGroups + 1).max(1)

  val io = IO(new Bundle {
    val spikesIn       = Input(UInt(inputVectorWidth.W))
    val spikesOut      = Output(UInt(outputVectorWidth.W))
    val configNLayers  = Output(UInt(layerCountWidth.W))
    val configNInputs  = Output(UInt(layerSizeWidth.W))
    val configNOutputs = Output(UInt(layerSizeWidth.W))
    val start          = Input(Bool())
    val busy           = Output(Bool())
    val done           = Output(Bool())
    val sram           = new SramIO(sramAddrWidth, sramDataWidth)
  })

  // --- State machine ---
  val sIdle :: sConfigRead :: sRead :: sApply :: sNextGroup :: sDone :: Nil = Enum(6)
  val state         = RegInit(sIdle)
  val layerIdx      = RegInit(0.U(log2Ceil(maxSupportedLayers).max(1).W))
  val groupIdx      = RegInit(0.U(groupWidth.W))
  val inputIdx      = RegInit(0.U(log2Ceil(maxInputsPerLayer + 1).max(1).W))
  val configReadIdx = RegInit(0.U(log2Ceil(maxSupportedLayers + 2).max(1).W))

  // Spike buffers
  val spikesBuf    = RegInit(0.U(maxLayerWidth.W))
  val spikesOutReg = RegInit(0.U(outputVectorWidth.W))

  // Accumulator for current sub-group (4 neurons)
  val accum = RegInit(VecInit(Seq.fill(4)(0.S(neuronWidth.W))))

  // Output spike accumulator: collects results across sub-groups for current layer
  val layerSpikes = RegInit(0.U(maxOutputsPerLayer.W))

  // Runtime-loaded layer metadata
  val runtimeNLayers = RegInit(0.U(layerCountWidth.W))
  val runtimeLayerSizes = RegInit(VecInit(Seq.fill(maxSupportedLayers + 1)(0.U(layerSizeWidth.W))))
  val runtimeLayerInputs = RegInit(VecInit(Seq.fill(maxSupportedLayers)(0.U(layerSizeWidth.W))))
  val runtimeLayerOutputs = RegInit(VecInit(Seq.fill(maxSupportedLayers)(0.U(layerSizeWidth.W))))
  val runtimeLayerGroups = RegInit(VecInit(Seq.fill(maxSupportedLayers)(0.U(groupWidth.W))))
  val runtimeLayerBases = RegInit(VecInit(Seq.fill(maxSupportedLayers)(0.U(sramAddrWidth.W))))
  val previousLayerSize = RegInit(0.U(layerSizeWidth.W))
  val firstWeightBase = RegInit(0.U(sramAddrWidth.W))
  val nextWeightBase = RegInit(0.U(sramAddrWidth.W))

  private def lookupUInt(vec: Vec[UInt], idx: UInt): UInt = {
    if (vec.length == 1) vec.head
    else Mux1H(vec.zipWithIndex.map { case (value, i) => (idx === i.U) -> value })
  }

  private def spikeAt(idx: UInt): Bool = {
    if (maxLayerWidth == 1) spikesBuf(0)
    else Mux1H((0 until maxLayerWidth).map(i => (idx === i.U) -> spikesBuf(i)))
  }

  private def clearAccum(): Unit = {
    for (j <- 0 until 4) {
      accum(j) := 0.S
    }
  }

  // Compute SRAM address: base + input * nGroups + group
  def sramAddr(layer: UInt, input: UInt, group: UInt): UInt = {
    lookupUInt(runtimeLayerBases, layer) + input * lookupUInt(runtimeLayerGroups, layer) + group
  }

  // Defaults
  io.sram.clk  := clock
  io.sram.csb  := true.B
  io.sram.web  := true.B
  io.sram.addr := 0.U
  io.sram.din  := 0.U
  io.busy := state =/= sIdle
  io.done := false.B
  io.spikesOut := spikesOutReg
  io.configNLayers := runtimeNLayers
  io.configNInputs := runtimeLayerSizes.head
  io.configNOutputs := lookupUInt(runtimeLayerSizes, runtimeNLayers)

  switch(state) {
    is(sIdle) {
      when(io.start) {
        spikesBuf      := io.spikesIn
        spikesOutReg   := 0.U
        runtimeNLayers := 0.U
        configReadIdx  := 0.U
        previousLayerSize := 0.U
        firstWeightBase   := 0.U
        nextWeightBase    := 0.U
        layerIdx       := 0.U
        groupIdx       := 0.U
        inputIdx       := 0.U
        layerSpikes := 0.U
        clearAccum()
        state := sConfigRead
        io.sram.csb  := false.B
        io.sram.addr := 0.U
      }
    }

    is(sConfigRead) {
      when(configReadIdx === 0.U) {
        val headerNLayers = io.sram.dout(layerCountWidth - 1, 0)
        val headerBase = Wire(UInt(sramAddrWidth.W))
        headerBase := headerNLayers.pad(sramAddrWidth) + 2.U(sramAddrWidth.W)
        runtimeNLayers := headerNLayers
        firstWeightBase := headerBase
        nextWeightBase  := headerBase
        configReadIdx  := 1.U
        io.sram.csb    := false.B
        io.sram.addr   := 1.U
      }.otherwise {
        val sizeWord = io.sram.dout(layerSizeWidth - 1, 0)
        val sizeEntryIdx = configReadIdx - 1.U
        for (i <- 0 until maxSupportedLayers + 1) {
          when(sizeEntryIdx === i.U) {
            runtimeLayerSizes(i) := sizeWord
          }
        }

        when(configReadIdx =/= 1.U) {
          val completedLayerIdx = configReadIdx - 2.U
          val groups = ((sizeWord + 3.U) >> 2)(groupWidth - 1, 0)
          for (i <- 0 until maxSupportedLayers) {
            when(completedLayerIdx === i.U) {
              runtimeLayerInputs(i)  := previousLayerSize
              runtimeLayerOutputs(i) := sizeWord
              runtimeLayerGroups(i)  := groups
              runtimeLayerBases(i)   := nextWeightBase
            }
          }
          nextWeightBase := nextWeightBase + previousLayerSize * groups
        }

        previousLayerSize := sizeWord

        val lastConfigIdx = Wire(UInt(configReadIdx.getWidth.W))
        lastConfigIdx := runtimeNLayers.pad(configReadIdx.getWidth) + 1.U(configReadIdx.getWidth.W)

        when(configReadIdx === lastConfigIdx) {
          layerIdx    := 0.U
          groupIdx    := 0.U
          inputIdx    := 0.U
          layerSpikes := 0.U
          clearAccum()
          state := sRead
          io.sram.csb  := false.B
          io.sram.addr := firstWeightBase
        }.otherwise {
          val nextConfigIdx = configReadIdx + 1.U
          configReadIdx := nextConfigIdx
          io.sram.csb  := false.B
          io.sram.addr := nextConfigIdx
        }
      }
    }

    is(sRead) {
      val curNIn  = lookupUInt(runtimeLayerInputs, layerIdx)
      val curNOut = lookupUInt(runtimeLayerOutputs, layerIdx)

      // How many valid neurons in this sub-group
      val neuronsInGroup = Mux(
        (groupIdx +& 1.U) * 4.U > curNOut,
        curNOut - groupIdx * 4.U,
        4.U
      )

      // Accumulate from arrived SRAM data
      for (j <- 0 until 4) {
        val wRaw = io.sram.dout((j + 1) * weightWidth - 1, j * weightWidth)
        val wSigned = wRaw.asSInt
        when(j.U < neuronsInGroup && spikeAt(inputIdx)) {
          accum(j) := accum(j) + wSigned.pad(neuronWidth)
        }
      }

      when(inputIdx === curNIn - 1.U) {
        state := sApply
      }.otherwise {
        val nextInput = inputIdx + 1.U
        io.sram.csb  := false.B
        io.sram.addr := sramAddr(layerIdx, nextInput, groupIdx)
        inputIdx := nextInput
      }
    }

    is(sApply) {
      val curNOut = lookupUInt(runtimeLayerOutputs, layerIdx)

      // How many valid neurons in this sub-group
      val neuronsInGroup = Mux(
        (groupIdx +& 1.U) * 4.U > curNOut,
        curNOut - groupIdx * 4.U,
        4.U
      )

      // Inline threshold comparison (stateless)
      val spikeBits = Wire(Vec(4, Bool()))
      for (j <- 0 until 4) {
        spikeBits(j) := j.U < neuronsInGroup && accum(j) >= threshold.S(neuronWidth.W)
      }
      val subSpikes = spikeBits.asUInt

      // Place sub-group spikes at position groupIdx*4
      val mask = (subSpikes & ((1.U << neuronsInGroup) - 1.U))(maxOutputsPerLayer - 1, 0)
      layerSpikes := layerSpikes | (mask << (groupIdx * 4.U))

      state := sNextGroup
    }

    is(sNextGroup) {
      val curNGroups = lookupUInt(runtimeLayerGroups, layerIdx)

      when(groupIdx === curNGroups - 1.U) {
        // All sub-groups done for this layer
        when(layerIdx === runtimeNLayers - 1.U) {
          spikesOutReg := layerSpikes(outputVectorWidth - 1, 0)
          state := sDone
        }.otherwise {
          spikesBuf := layerSpikes
          val nextLayer = layerIdx + 1.U
          layerIdx    := nextLayer
          groupIdx    := 0.U
          inputIdx    := 0.U
          layerSpikes := 0.U
          clearAccum()

          io.sram.csb  := false.B
          io.sram.addr := sramAddr(nextLayer, 0.U, 0.U)
          state := sRead
        }
      }.otherwise {
        val nextGroup = groupIdx + 1.U
        groupIdx := nextGroup
        inputIdx := 0.U
        clearAccum()

        io.sram.csb  := false.B
        io.sram.addr := sramAddr(layerIdx, 0.U, nextGroup)
        state := sRead
      }
    }

    is(sDone) {
      io.done := true.B
      state   := sIdle
    }
  }
}
