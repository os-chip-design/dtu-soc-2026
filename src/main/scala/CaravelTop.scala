import circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import wishbone.WishboneIO
import wildcat.pipeline._
import videoController.VideoController

object CaravelTop extends App {
  emitVerilog(
    new CaravelTop(), 
    Array("--target-dir", "verilog/rtl")
  )
}


class CaravelTop extends Module {

  val WB_ADDR_WIDTH = 28
  val MPRJ_IO_PADS = 38

  val wb = IO(Flipped(new WishboneIO(WB_ADDR_WIDTH)))
  val io = IO(new Bundle {
    val in = Input(UInt(MPRJ_IO_PADS.W))
    val out = Output(UInt(MPRJ_IO_PADS.W))
    val oeb = Output(UInt(MPRJ_IO_PADS.W))
  })
  // Wildcat Integration
  val wc = Module(new CpuTop("a.out"))

  val led = wc.io.led
  val tx = wc.io.tx
  wc.io.rx := false.B

  // Dummy assignments to avoid unconnected IOs
  io.out := led << 8.U
  io.oeb := ~(1.U(38.W) << 8.U)

  // TODO: instantiate the wishbone GPIO peripheral
  // TODO: connect the GPIO peripheral to the Wishbone bus
  // TODO: set the wb.cyc port to 0 as a default
  // TODO: connect the GPIO peripheral's input and output ports to the top-level IO

  // create dummy gcd peripheral for testing
  val gcd = Module(new WishboneGcd(16))
  gcd.wb <> wb
  gcd.wb.cyc := 0.B

  val vc = Module(new VideoController)
  io.out := led ## vc.io.hSync ## vc.io.vSync ## vc.io.red ## vc.io.green ## vc.io.blue
  io.oeb := ~("x00000001FF".U)

  // address decoding for the peripherals
  // lower 16 bits of the address are used inside the peripherals, so we ignore them for decoding
  // the upper 4 bits [19:16] are used for decoding
  switch(wb.addr(WB_ADDR_WIDTH - 1, WB_ADDR_WIDTH - 8)) {
    is(0x0.U) {
      // TODO: connect the GPIO peripheral to the Wishbone bus
    }
    is(0x1.U) {
      gcd.wb.cyc := wb.cyc
      wb.ack := gcd.wb.ack
      wb.rdData := gcd.wb.rdData
    }
  }
  val imem = Module(new Programmable_IMEM(depth = 8)) // depth = 1024 words
  
  // Set defaults first
imem.io.wb.addr := 0.U
imem.io.wb.wrData := 0.U
imem.io.wb.we := false.B
imem.io.wb.stb := false.B
imem.io.wb.cyc := false.B
imem.io.wb.sel := 0.U

wb.ack := false.B
wb.rdData := 0.U

// Address decoding
switch(wb.addr(WB_ADDR_WIDTH - 1, WB_ADDR_WIDTH - 4)) {
  is(0x3.U) { 
    // Connect bus signals to IMEM
    imem.io.wb.addr := wb.addr
    imem.io.wb.wrData := wb.wrData
    imem.io.wb.we := wb.we
    imem.io.wb.stb := wb.stb
    imem.io.wb.cyc := wb.cyc
    imem.io.wb.sel := wb.sel

    // Connect back the outputs
    wb.ack := imem.io.wb.ack
    wb.rdData := imem.io.wb.rdData
  }
}
}
