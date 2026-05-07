import chisel3._

package object wishbone {
  class WishboneIO(addrWidth: Int, dataWidth: Int) extends Bundle {
    val cyc  = Input(Bool())
    val stb  = Input(Bool())
    val we   = Input(Bool())
    val addr = Input(UInt(addrWidth.W))
    val din  = Input(UInt(dataWidth.W))
    val dout = Output(UInt(dataWidth.W))
    val sel  = Input(UInt((dataWidth / 8).W))
    val ack  = Output(Bool())
  }
}
