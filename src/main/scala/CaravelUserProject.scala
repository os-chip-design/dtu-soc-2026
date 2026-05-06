import circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import wishbone.WishboneIO
import wildcat.pipeline._
import programmable_IMEM.programmable_IMEM
import comm_controller.comm_controller

object CaravelUserProject extends App {
  emitVerilog(
    new CaravelUserProject(), 
    Array("--target-dir", "verilog/rtl")
  )
}

class CaravelUserProject extends Module {

  val WB_ADDR_WIDTH = 32
  val MPRJ_IO_PADS = 38

  val wb = IO(Flipped(new WishboneIO(WB_ADDR_WIDTH)))
  val io = IO(new Bundle {
    val in = Input(UInt(MPRJ_IO_PADS.W))
    val out = Output(UInt(MPRJ_IO_PADS.W))
    val oeb = Output(UInt(MPRJ_IO_PADS.W))
  })

  // Wildcate Reset Register
  val wcReset = Reg(Bool())
  wcReset := reset

  // Wildcat Integration
  val wc = Module(new CpuTop())
  wc.reset := wcReset
  wc.io.wb <> wb
  wc.io.wb.cyc := 0.B
  wc.io.wb_2 <> wb
  wc.io.wb_2.cyc := 0.B
  

  val led = wc.io.led
  val tx = wc.io.tx

  // create dummy gpio peripheral for testing
  /*
  val gpio = Module(new WishboneGpio(8))
  gpio.wb <> wb
  gpio.wb.cyc := 0.B
  */

  val comm = Module(new comm_controller())
  comm.wb <> wb
  comm.wb.cyc := 0.B
  wc.io.cpu_reset := comm.cpu_reset
  wc.io.imem_sel := comm.imem_sel

  val com = Module(new WildcatCaravelCommunication())
  com.wb <> wb
  com.wb.cyc := 0.B

  //connect com io to wildcat io 
  com.io.fromWildcat := wc.io.comWriteData
  com.io.wildcatWriteValid := wc.io.comWriteValid
  wc.io.comReadData := com.io.toWildcat


  // create dummy gcd peripheral for testing
  val gcd = Module(new WishboneGcd(16))
  gcd.wb <> wb
  gcd.wb.cyc := 0.B

  // create luke_clock peripheral
  val lukeClock = Module(new WishboneLukeClock)
  lukeClock.wb <> wb
  lukeClock.wb.cyc := 0.B

  val spiPmod = Module(new WishboneSpiPmod)
  spiPmod.wb <> wb
  spiPmod.wb.cyc := 0.B
  spiPmod.ctrl <> wc.io.flashCtrl
  wc.io.progMode := spiPmod.progMode

  // val imem = Module(new programmable_IMEM(depth = 16)) // depth = 1024 words
    // imem.wb<>wb
    // imem.wb.cyc:=0.B

  // address decoding for the peripherals
  // lower 20 bits of the address are used inside the peripherals, so we ignore them for decoding
  // bits [27:20] are used for decoding
  switch(wb.addr(27, 20)) {
    /*
    is(0x0.U) {
      gpio.wb.cyc := wb.cyc
      wb.ack := gpio.wb.ack
      wb.rdData := gpio.wb.rdData
    }
    */
    is(0x1.U) {
      gcd.wb.cyc := wb.cyc
      wb.ack := gcd.wb.ack
      wb.rdData := gcd.wb.rdData
    }
    is(0x2.U) {
      wc.io.wb.cyc := wb.cyc
      wb.ack := wc.io.wb.ack
      wb.rdData := wc.io.wb.rdData
    }
    is(0x3.U){
      wc.io.wb_2.cyc := wb.cyc
      wb.ack := wc.io.wb_2.ack
      wb.rdData := wc.io.wb_2.rdData
    }
    // is (0x4.U) {
    //   wcReset := true.B
    // }
    is (0x4.U){
      comm.wb.cyc := wb.cyc
      wb.ack := comm.wb.ack
      wb.rdData := comm.wb.rdData
    }
    is(0x6.U) {
      spiPmod.wb.cyc := wb.cyc
      wb.ack := spiPmod.wb.ack
      wb.rdData := spiPmod.wb.rdData
    }
    is(0x7.U) {
      lukeClock.wb.cyc := wb.cyc
      wb.ack := lukeClock.wb.ack
      wb.rdData := lukeClock.wb.rdData
    }
    is (0x5.U) {
      com.wb.cyc := wb.cyc
      wb.ack := com.wb.ack
      wb.rdData := com.wb.rdData
    }
  }

  val lc = Module(new LittleCat(10000000, 115200))

  val outVec = WireInit(VecInit(Seq.fill(MPRJ_IO_PADS)(0.U(1.W))))
  val oebVec = WireInit(VecInit(Seq.fill(MPRJ_IO_PADS)(0.U(1.W))))

  // UART TX on pin 7
  outVec(7) := tx

  // LitlleCat on pins 8..15
  outVec(8) := lc.io.out(0)
  outVec(9) := lc.io.out(1)
  lc.io.in := io.in(11, 10)
  oebVec(10) := true.B
  oebVec(11) := true.B 
  lc.io.rx := io.in(12)
  oebVec(12) := true.B
  outVec(13) := lc.io.tx
  lc.io.rxConf := io.in(14)
  oebVec(14) := true.B
  outVec(15) := lc.io.txConf


  /*
  // GPIO 15..8
  for (i <- 0 until 8) {
    outVec(8 + i) := gpio.io.out(i)
    oebVec(8 + i) := gpio.io.oeb(i)
  }
  gpio.io.in := io.in(15, 8)
    */

  // LED on pin 24
  outVec(24) := led(0)

  // UART RX on pin 25
  wc.io.rx := io.in(25)
  oebVec(25) := true.B

  // QSPI pmod: primary SPI on 29..26, PSRAM chip selects on 23..22
  // sd2 (WP#) and sd3 (HOLD#) are always driven high inside SpiFlashController;
  // no pad needed — tie high on the PMOD board with pull-ups.
  outVec(26) := wc.io.pmod.cs0   // flash chip select
  outVec(27) := wc.io.pmod.sd0   // MOSI
  wc.io.pmod.sd1 := io.in(28)    // MISO — input
  oebVec(28) := true.B
  outVec(29) := wc.io.pmod.sck   // clock
  outVec(22) := wc.io.pmod.cs1   // PSRAM A chip select
  outVec(23) := wc.io.pmod.cs2   // PSRAM B chip select

  // VGA out
  outVec(30) := RegNext(RegNext(RegNext( Mux(comm.vga_sel, lukeClock.io.hSyncOut, wc.video.hSync) )))
  outVec(31) := RegNext(RegNext(RegNext( Mux(comm.vga_sel, lukeClock.io.vSyncOut, wc.video.vSync) )))
  outVec(32) := RegNext(RegNext(RegNext( Mux(comm.vga_sel, lukeClock.io.redOut(0), wc.video.red(0)) )))
  outVec(33) := RegNext(RegNext(RegNext( Mux(comm.vga_sel, lukeClock.io.redOut(1), wc.video.red(1)) )))
  outVec(34) := RegNext(RegNext(RegNext( Mux(comm.vga_sel, lukeClock.io.greenOut(0), wc.video.green(0)) )))
  outVec(35) := RegNext(RegNext(RegNext( Mux(comm.vga_sel, lukeClock.io.greenOut(1), wc.video.green(1)) )))
  outVec(36) := RegNext(RegNext(RegNext( Mux(comm.vga_sel, lukeClock.io.blueOut(0), wc.video.blue(0)) )))
  outVec(37) := RegNext(RegNext(RegNext( Mux(comm.vga_sel, lukeClock.io.blueOut(1), wc.video.blue(1)) )))

  // ==========================================
  // GROUP 5 SPI PMOD (Pins 16 to 23)
  outVec(16) := wc.io.g5_spi_cs0_n
  outVec(17) := wc.io.g5_spi_mosi
  wc.io.g5_spi_miso := io.in(18)
  oebVec(18) := true.B                 // pin 18 is input
  outVec(19) := wc.io.g5_spi_sck
  outVec(20) := wc.io.g5_spi_cs1_n
  outVec(21) := wc.io.g5_spi_cs2_n
  // ==========================================

  // Group 4 raytracer dedicated UART TX (pin 6).
  outVec(6) := wc.io.rayTx


  io.out := outVec.asUInt
  io.oeb := oebVec.asUInt
}
