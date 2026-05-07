import chisel3._
import chisel3.util.Cat
import wishbone.WishboneIO

class WishboneSnnBlackBox extends BlackBox {
  override def desiredName: String = "WishboneSnn"

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val wb_cyc = Input(Bool())
    val wb_stb = Input(Bool())
    val wb_we = Input(Bool())
    val wb_addr = Input(UInt(32.W))
    val wb_din = Input(UInt(32.W))
    val wb_dout = Output(UInt(32.W))
    val wb_sel = Input(UInt(4.W))
    val wb_ack = Output(Bool())
    val io_spikesOut = Output(UInt(4.W))
  })
}

class WishboneSnnPeripheral extends Module {
  val wb = IO(Flipped(new WishboneIO(32)))
  val io = IO(new Bundle {
    val spikesOut = Output(UInt(4.W))
  })

  val snn = Module(new WishboneSnnBlackBox)
  val localAddr = Cat(0.U(20.W), wb.addr(11, 0))

  snn.io.clock := clock
  snn.io.reset := reset.asBool
  snn.io.wb_cyc := wb.cyc
  snn.io.wb_stb := wb.stb
  snn.io.wb_we := wb.we
  snn.io.wb_addr := localAddr
  snn.io.wb_din := wb.wrData
  snn.io.wb_sel := wb.sel

  wb.rdData := snn.io.wb_dout
  wb.ack := snn.io.wb_ack
  io.spikesOut := snn.io.io_spikesOut
}
