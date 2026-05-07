package OpenRam

import chisel3._

class sky130_sram_1kbyte_1rw1r_32x256_8 extends BlackBox {
  val io = IO(new Bundle {
    // Port 0: RW
    val clk0 = Input(Clock())
    val csb0 = Input(Bool())
    val web0 = Input(Bool())
    val wmask0 = Input(UInt(4.W))
    val addr0 = Input(UInt(8.W))
    val din0 = Input(UInt(32.W))
    val dout0 = Output(UInt(32.W))

    // Port 1: R
    val clk1 = Input(Clock())
    val csb1 = Input(Bool())
    val addr1 = Input(UInt(8.W))
    val dout1 = Output(UInt(32.W))
  })
}
