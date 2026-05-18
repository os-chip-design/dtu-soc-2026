package comm_controller
import chisel3._
import chisel3.util._
import wishbone._
import chisel3.util.log2Floor
import OpenRam._
import soc._
import wishbone.WishboneIO

class comm_controller() extends Module {

  val wb = IO(Flipped(new WishboneIO(32)))
  val cpu_reset = IO(Output(Bool()))
  val imem_sel = IO(Output(Bool()))
  val vga_sel = IO(Output(Bool()))
  val picoMeshUartSel = IO(Output(Bool()))

  val ackReg = RegInit(false.B)
  val reset_reg = RegInit(0.U(32.W))

  // ACK logic
  wb.ack := ackReg
  when (ackReg) {
    ackReg := false.B
  }.elsewhen (wb.cyc && wb.stb) {
    ackReg := true.B
  }

  // Write logic
  when (wb.cyc && wb.stb && wb.we) {
    reset_reg := wb.wrData
  }

  // Read logic
  wb.rdData := reset_reg

  // Output
  cpu_reset := reset_reg(0)
  imem_sel := reset_reg(1)
  vga_sel := reset_reg(2)
  picoMeshUartSel := reset_reg(3)
}
