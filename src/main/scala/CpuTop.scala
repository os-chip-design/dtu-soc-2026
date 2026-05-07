import chisel.lib.uart._
import chisel3._
import chisel3.util.RegEnable
import wildcat.Util
import wildcat.pipeline._
import memory._
import device._
import videoController.VideoController
import raytracer.RayTracerController
import wishbone.WishboneIO
import programmable_IMEM.programmable_IMEM

/*
 * This file is a modification of the RISC-V processor Wildcat
 * for implementation in Caravel.
 *
 * This is the top-level for a three stage pipeline.
 * A copy of WildcatTop.
 *
 * Author: Martin Schoeberl (martin@jopdesign.com)
 *
 * Edited by F26 02118
 *
 */
class CpuTop(dmemNrByte: Int = 16) extends Module {
  val io = IO(new Bundle {
    val led = Output(UInt(16.W))
    val tx = Output(UInt(1.W))
    val rx = Input(UInt(1.W))
    val rayTx = Output(UInt(1.W))
    val wb = Flipped(new WishboneIO(32))
    val pmod = new QspiPmodIO
    val flashCtrl = new FlashCtrlIO
    val progMode = Input(Bool())
    val comReadData = Input(UInt(32.W))
    val comWriteData = Output(UInt(32.W))
    val comWriteValid = Output(Bool())
    val imem_sel = Input(Bool())
    val wb_2 = Flipped(new WishboneIO(32))
    val cpu_reset = Input(Bool())

    // GROUP 5 PINS
    val g5_spi_sck   = Output(Bool())
    val g5_spi_mosi  = Output(Bool())
    val g5_spi_miso  = Input(Bool())
    val g5_spi_cs0_n = Output(Bool())
    val g5_spi_cs1_n = Output(Bool())
    val g5_spi_cs2_n = Output(Bool())
  })

  val video = IO(new Bundle {
    val hSync = Output(Bool())
    val vSync = Output(Bool())
    val red = Output(UInt(2.W))
    val green = Output(UInt(2.W))
    val blue = Output(UInt(2.W))
  })

  val cpu = Module(new ThreeCats())
  val dmem = Module(new DataMemory()) //val dmem = Module(new ScratchPadMem(memory, nrBytes = dmemNrByte))
  val imem = Module(new WishboneInstrRam) //val imem = Module(new InstructionROM(memory))
  val imem_2 = Module(new(programmable_IMEM))
  val imem_mux = Module(new(imem_mux))
  cpu.reset := io.cpu_reset
  imem.io.reset := io.cpu_reset
  val cache = Module(new DataCache())
  val spiMem = Module(new SpiFlashController())

  // cpu.io.imem <> imem.io.cpu  //cpu.io.imem <> imem.io
  imem.io.cpu<>imem_mux.imem_0
  imem_2.cpu_connection <> imem_mux.imem_1
  imem_2.cpu_enable := !io.cpu_reset
  imem_mux.sel <> io.imem_sel
  cpu.io.imem <> imem_mux.cpu
  imem.io.wb <> io.wb
  io.pmod <> spiMem.io.spi
  spiMem.io.ctrl <> io.flashCtrl
  spiMem.io.progMode := io.progMode
  io.wb_2 <> imem_2.wb

  // ------------------------------------------------
  // Memory Connections
  // ------------------------------------------------
  // Direct CPU memory request signals
  val memAddress = cpu.io.dmem.address
  val memRd      = cpu.io.dmem.rd
  val memWr      = cpu.io.dmem.wr
  val memWrData  = cpu.io.dmem.wrData
  val memWrMask  = cpu.io.dmem.wrMask

  val memSelect = memAddress(31, 28)

  // Default cache CPU-side inputs
  cache.io.cpuIO.address := memAddress
  cache.io.cpuIO.rd      := false.B
  cache.io.cpuIO.wr      := false.B
  cache.io.cpuIO.wrData  := memWrData
  cache.io.cpuIO.wrMask  := memWrMask

  // Default cache backing-memory inputs
  cache.io.memIO.rdData := 0.U
  cache.io.memIO.ack    := false.B

  // Default SPI cache values
  spiMem.io.mem.address := 0.U
  spiMem.io.mem.rd      := false.B
  spiMem.io.mem.wr      := false.B
  spiMem.io.mem.wrData  := 0.U
  spiMem.io.mem.wrMask  := 0.U

  // ---------------------------------
  // GROUP 5: QSPI PMOD CONTROLLER
  val g5SpiCtrl = Module(new SpiMemoryController())

  io.g5_spi_sck   := g5SpiCtrl.io.spi.sck
  io.g5_spi_mosi  := g5SpiCtrl.io.spi.mosi
  g5SpiCtrl.io.spi.miso := io.g5_spi_miso
  io.g5_spi_cs0_n := g5SpiCtrl.io.spi.cs0_n
  io.g5_spi_cs1_n := g5SpiCtrl.io.spi.cs1_n
  io.g5_spi_cs2_n := g5SpiCtrl.io.spi.cs2_n

  // Default values
  g5SpiCtrl.io.pipecon.address := memAddress
  g5SpiCtrl.io.pipecon.wrData  := memWrData
  g5SpiCtrl.io.pipecon.wrMask  := memWrMask
  g5SpiCtrl.io.pipecon.rd      := false.B
  g5SpiCtrl.io.pipecon.wr      := false.B
  // -----------------------------------

  // Here data memory and IO stuff
  // data memory is at 0x0000_0000
  // IO is mapped ot 0xf000_0000
  // use lower bits to select IOs
  // bits 19..16 are used to select IO devices

  // UART:
  // 0xf000_0000 status:
  // bit 0 TX ready (TDE)
  // bit 1 RX data available (RDF)
  // 0xf000_0004 send and receive register

  // Cache
  // 0xe000_0000 - 0xefff_ffff

  // Address register for read multiplexing
  val memAddressReg = RegEnable(cpu.io.dmem.address, 0.U, cpu.io.dmem.rd)

  val csMem = cpu.io.dmem.address(31, 28) === 0.U

  // Default access is to data memory
  cpu.io.dmem <> dmem.io
  dmem.io.rd := csMem && cpu.io.dmem.rd
  dmem.io.wr := csMem && cpu.io.dmem.wr

  val csIO = cpu.io.dmem.address(31, 28) === 0xf.U
  val csIOReg = memAddressReg(31, 28) === 0xf.U
  val ioDecodeAddress = cpu.io.dmem.address(19,16)
  val ioDecodeAddressReg = memAddressReg(19, 16)

  // Everyone needs a UART
  val uartDevice = Module(new UartDevice(10000000, 115200))
  io.tx := uartDevice.io.txd
  uartDevice.io.rxd := io.rx

  val csUart = csIO && ioDecodeAddress === 0.U
  val muxUart = csIOReg && ioDecodeAddressReg === 0.U
  uartDevice.cpuPort <> cpu.io.dmem
  uartDevice.cpuPort.rd := csUart && cpu.io.dmem.rd
  uartDevice.cpuPort.wr := csUart && cpu.io.dmem.wr

  // We also love to have an LED to blink
  val ledDevice = Module(new LedDevice(16))
  io.led := RegNext(ledDevice.io.leds)

  val csLed = csIO && ioDecodeAddress === 1.U
  val muxLed = csIOReg && ioDecodeAddressReg === 1.U
  ledDevice.cpuPort <> cpu.io.dmem
  ledDevice.cpuPort.rd := csLed && cpu.io.dmem.rd
  ledDevice.cpuPort.wr := csLed && cpu.io.dmem.wr

  // Video Controller
  val vc = Module(new VideoController)
  video <> vc.video

  val csVc = csIO && ioDecodeAddress === 2.U
  vc.io.address := cpu.io.dmem.address
  vc.io.wrData := cpu.io.dmem.wrData
  vc.io.wr := csVc && cpu.io.dmem.wr

  val videoAckReg = RegInit(false.B)
  videoAckReg := false.B
  when (csVc) {
    videoAckReg := true.B
  }

  //Wildcat Caravel communication
  val csComm = csIO && ioDecodeAddress === 3.U
  val muxComm = csIOReg && ioDecodeAddressReg === 3.U
  io.comWriteData := cpu.io.dmem.wrData
  io.comWriteValid := csComm

  val commAckReg = RegInit(false.B)
  commAckReg := false.B
  when (csComm) {
    commAckReg := true.B
  }

  // Floating Point Peripheral
  val fpp = Module(new FloatingPointPeripheral)
  val csFpp = csIO && ioDecodeAddress === 4.U
  val muxFpp = csIOReg && ioDecodeAddressReg === 4.U
  fpp.io <> cpu.io.dmem
  fpp.io.rd := csFpp && cpu.io.dmem.rd
  fpp.io.wr := csFpp && cpu.io.dmem.wr

  // Group 4: ray-tracer accelerator + dedicated UART TX. MMIO at 0xf005_0000.
  // Reset gated on cpu_reset too so the firmware can re-arm the FSM after
  // the mgmt CPU loads IMEM (otherwise random IMEM bits can spuriously pulse
  // start during boot).
    io.rayTx := false.B // rayTxUart.io.txd

  /*
  val rayController = withReset(reset.asBool || io.cpu_reset) {
    Module(new RayTracerController)
  }
  val rayTxUart = Module(new BufferedTx(10000000, 115200))
  rayTxUart.io.channel <> rayController.io.byteOut
  io.rayTx := rayTxUart.io.txd

  val csRayController  = csIO && ioDecodeAddress === 5.U
  val muxRayController = csIOReg && ioDecodeAddressReg === 5.U
  rayController.io.address := memAddress(15, 0)
  rayController.io.wr      := csRayController && memWr
  rayController.io.rd      := csRayController && memRd
  rayController.io.wrData  := memWrData
  rayController.io.wrMask  := memWrMask

  val rayCtrlRdDataReg = RegNext(rayController.io.rdData)
  val rayCtrlAckReg    = RegInit(false.B)
  rayCtrlAckReg := false.B
  when (csRayController && (memRd || memWr)) {
    rayCtrlAckReg := true.B
  }
  */

  // TODO: move to the bottom and have all devices in one statement
  // read mux for memory and IO devices
  cpu.io.dmem.rdData := dmem.io.rdData
  when (muxUart) {
    cpu.io.dmem.rdData := uartDevice.cpuPort.rdData
  } .elsewhen(muxLed) {
    cpu.io.dmem.rdData := RegNext(ledDevice.io.leds)
  } .elsewhen(muxComm) {
    cpu.io.dmem.rdData := io.comReadData
  } .elsewhen(muxFpp) {
    cpu.io.dmem.rdData := fpp.io.rdData
    /*
  } .elsewhen(muxRayController) {
    cpu.io.dmem.rdData := rayCtrlRdDataReg
      */
  }
  // or reduce all ack signals
  cpu.io.dmem.ack := dmem.io.ack || uartDevice.cpuPort.ack || ledDevice.cpuPort.ack || videoAckReg || commAckReg || fpp.io.ack // || rayCtrlAckReg

  // ------------------------------------------------
  // Memory
  // ------------------------------------------------
  when (memSelect === 0xc.U) {
    // 0xC000_0000 - 0xC7FF_FFFF -> direct uncached PSRAM A
    spiMem.io.mem.address := "h1".U(4.W) ## memAddress(27, 0)
    spiMem.io.mem.rd      := memRd
    spiMem.io.mem.wr      := memWr
    spiMem.io.mem.wrData  := memWrData
    spiMem.io.mem.wrMask  := memWrMask

    cpu.io.dmem.rdData := spiMem.io.mem.rdData
    cpu.io.dmem.ack    := spiMem.io.mem.ack
  }
  when (memSelect === 0xd.U) {
    // 0xD800_0000 - 0xDFFF_FFFF -> direct uncached PSRAM B
    spiMem.io.mem.address := "h2".U(4.W) ## memAddress(27, 0)
    spiMem.io.mem.rd      := memRd
    spiMem.io.mem.wr      := memWr
    spiMem.io.mem.wrData  := memWrData
    spiMem.io.mem.wrMask  := memWrMask

    cpu.io.dmem.rdData := spiMem.io.mem.rdData
    cpu.io.dmem.ack    := spiMem.io.mem.ack
  }
  when (memSelect === 0xe.U) { // CACHE 0xE, read-only cached PSRAM/flash window
    // CPU -> cache
    cache.io.cpuIO.address := memAddress
    cache.io.cpuIO.rd      := memRd
    cache.io.cpuIO.wr      := false.B
    cache.io.cpuIO.wrData  := memWrData
    cache.io.cpuIO.wrMask  := memWrMask

    // cache -> spiMem
    spiMem.io.mem.address := cache.io.memIO.address
    spiMem.io.mem.rd      := cache.io.memIO.rd
    spiMem.io.mem.wr      := false.B
    spiMem.io.mem.wrData  := 0.U
    spiMem.io.mem.wrMask  := 0.U

    // spiMem -> cache
    cache.io.memIO.rdData := spiMem.io.mem.rdData
    cache.io.memIO.ack    := spiMem.io.mem.ack

    // cache -> CPU
    when (memWr) {
      // Read-only cache region: ignore writes but acknowledge them.
      cpu.io.dmem.rdData := 0.U
      cpu.io.dmem.ack    := true.B
    } .otherwise {
      cpu.io.dmem.rdData := cache.io.cpuIO.rdData
      cpu.io.dmem.ack    := cache.io.cpuIO.ack
    }
  }

  // ------------------------------------------------
  // GROUP 5: Memory Mapping (0x4, 0x5, 0x6)
  // ------------------------------------------------
  val isG5Access = (memSelect === "h4".U) || (memSelect === "h5".U) || (memSelect === "h6".U)

  when (isG5Access) {
    g5SpiCtrl.io.pipecon.rd := memRd
    g5SpiCtrl.io.pipecon.wr := memWr

    cpu.io.dmem.rdData := g5SpiCtrl.io.pipecon.rdData
    cpu.io.dmem.ack    := g5SpiCtrl.io.pipecon.ack
  }

}

object CpuTop extends App {
  emitVerilog(new CpuTop(), Array("--target-dir", "generated"))
}
