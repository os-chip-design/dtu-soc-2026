import chisel3._
import chisel3.util._
import OpenRam.sky130_sram_1kbyte_1rw1r_32x256_8

class WishboneMultiLayerSnnCoreIO(
  nInputs: Int,
  nOutputs: Int,
  layerCountWidth: Int,
  layerSizeWidth: Int,
  sramAddrWidth: Int,
  sramDataWidth: Int
) extends Bundle {
  val start    = Output(Bool())
  val spikesIn = Output(UInt(nInputs.W))
  val busy     = Input(Bool())
  val done     = Input(Bool())
  val spikesOut = Input(UInt(nOutputs.W))
  val configNLayers  = Input(UInt(layerCountWidth.W))
  val configNInputs  = Input(UInt(layerSizeWidth.W))
  val configNOutputs = Input(UInt(layerSizeWidth.W))
  val sram = new Bundle {
    val csb  = Input(Bool())
    val web  = Input(Bool())
    val addr = Input(UInt(sramAddrWidth.W))
    val din  = Input(UInt(sramDataWidth.W))
    val dout = Output(UInt(sramDataWidth.W))
  }
}

// Shared Wishbone/SRAM shell for the fixed-topology and flex-topology multilayer SNNs.
//
// Memory map (32-bit Wishbone, byte-addressed):
//   0x00  Control  (W: bit 0 = start, R: bit 0 = busy, bit 1 = done)
//   0x04  SpikesIn (W: set input spike vector)
//   0x08  SpikesOut(R: result from last inference)
//   0x0C  Config   (R: packed layer config for software introspection)
//         bits [3:0]   = nLayers
//         bits [11:4]  = nInputs
//         bits [19:12] = nOutputs
//   0x10+ SRAM blob  (R/W: word at SRAM addr = (wb_addr - 0x10) >> 2)
class WishboneMultiLayerSnnShell(
  layerSizes: Seq[Int]
) extends Module {
  require(layerSizes.length >= 2, "Need at least input and output layer")

  private val nInputs  = layerSizes.head
  private val nOutputs = layerSizes.last
  private val nLayers  = layerSizes.length - 1
  private val layerCountWidth = log2Ceil(nLayers + 1).max(1)
  private val layerSizeWidth  = log2Ceil(layerSizes.max + 1).max(1)

  private val sramDataWidth = 32
  private val sramAddrWidth = 8

  val wb = IO(new wishbone.WishboneIO(addrWidth = 32, dataWidth = 32))
  val io = IO(new Bundle {
    val spikesOut = Output(UInt(nOutputs.W))
  })
  val core = IO(new WishboneMultiLayerSnnCoreIO(
    nInputs = nInputs,
    nOutputs = nOutputs,
    layerCountWidth = layerCountWidth,
    layerSizeWidth = layerSizeWidth,
    sramAddrWidth = sramAddrWidth,
    sramDataWidth = sramDataWidth
  ))

  // --- SRAM ---
  val sramCsb   = Wire(Bool());    sramCsb   := true.B
  val sramWeb   = Wire(Bool());    sramWeb   := true.B
  val sramWmask = Wire(UInt(4.W)); sramWmask := "b1111".U
  val sramAddr  = Wire(UInt(sramAddrWidth.W)); sramAddr := 0.U
  val sramDin   = Wire(UInt(sramDataWidth.W)); sramDin  := 0.U
  val sramDout  = Wire(UInt(sramDataWidth.W))
  core.sram.dout := sramDout

  val ram = Module(new sky130_sram_1kbyte_1rw1r_32x256_8)
  ram.io.clk0   := clock
  ram.io.csb0   := sramCsb
  ram.io.web0   := sramWeb
  ram.io.wmask0 := sramWmask
  ram.io.addr0  := sramAddr
  ram.io.din0   := sramDin
  sramDout      := ram.io.dout0
  ram.io.clk1   := clock
  ram.io.csb1   := true.B
  ram.io.addr1  := 0.U

  // --- Registers ---
  val spikesInReg  = RegInit(0.U(nInputs.W))
  val spikesOutReg = RegInit(0.U(nOutputs.W))
  val startPulse   = WireDefault(false.B)
  val doneFlag     = RegInit(false.B)

  core.start    := startPulse
  core.spikesIn := spikesInReg

  // --- SRAM port mux: SNN core vs Wishbone ---
  val snnActive = core.busy || startPulse

  // --- Wishbone decode ---
  val wbActive     = wb.cyc && wb.stb
  val wbAddr       = wb.addr
  val wbWrite      = wbActive && wb.we
  val wbRead       = wbActive && !wb.we
  val regSel       = wbAddr(3, 2)
  val isSramAccess = wbAddr >= "h10".U
  val sramWbAddr   = wbAddr(9, 2) - 4.U

  wb.ack  := wbActive
  wb.dout := 0.U

  val configWord = Cat(
    0.U((32 - 20).W),
    core.configNOutputs.pad(8)(7, 0),
    core.configNInputs.pad(8)(7, 0),
    core.configNLayers.pad(4)(3, 0)
  )

  when(wbRead) {
    when(isSramAccess) {
      wb.dout := sramDout
    }.otherwise {
      switch(regSel) {
        is(0.U) { wb.dout := Cat(doneFlag, core.busy) }
        is(1.U) { wb.dout := spikesInReg }
        is(2.U) { wb.dout := spikesOutReg }
        is(3.U) { wb.dout := configWord }
      }
    }
  }

  when(wbWrite) {
    when(!isSramAccess) {
      switch(regSel) {
        is(0.U) { startPulse := wb.din(0) }
        is(1.U) { spikesInReg := wb.din(nInputs - 1, 0) }
      }
    }
  }

  when(snnActive) {
    sramCsb   := core.sram.csb
    sramWeb   := core.sram.web
    sramAddr  := core.sram.addr
    sramDin   := core.sram.din
    sramWmask := "b1111".U
  }.elsewhen(wbActive && isSramAccess) {
    sramCsb   := false.B
    sramWeb   := Mux(wb.we, false.B, true.B)
    sramAddr  := sramWbAddr
    sramDin   := wb.din
    sramWmask := wb.sel
  }

  when(core.done) {
    spikesOutReg := core.spikesOut
    doneFlag     := true.B
  }
  when(startPulse) {
    doneFlag := false.B
  }

  io.spikesOut := spikesOutReg
}
