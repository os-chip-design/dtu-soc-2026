import chisel3._
import chisel3.util._


/**
  * Wishbone n-bit GPIO peripheral with 3 registers:
  * - input register at address 0x0 (read-only)
  * - output register at address 0x4 (read-write)
  * - oeb register at address 0x8 (read-write) (1 = input, 0 = output)
  *
  * @param n number of GPIO pins (must be <= 32)
  */
class WishboneGpio(n: Int) extends Module {

  // we only need 4 bits to address the 3 registers (input, output, oeb)
  val WB_ADDR_WIDTH = 4

  require(n <= 32, "GPIO width cannot exceed 32 bits")
  
  val wb = IO(new wishbone.WishboneIO(addrWidth = WB_ADDR_WIDTH, dataWidth = 32))
  val io = IO(new Bundle {
    val in = Input(UInt (n.W))
    val out = Output(UInt (n.W))
    val oeb = Output(UInt (n.W))
  })

  // registers to hold the output and oeb values
  val outReg = RegInit(0.U(n.W))
  val oebReg = RegInit(0.U(n.W))

  // wishbone acknowledge register
  val ackReg = RegInit(0.B)
  when(ackReg) {
    ackReg := 0.B
  }.elsewhen(wb.cyc && wb.stb) {
    ackReg := 1.B
  }

  // address decoding for the registers
  val inAccess = wb.addr === 0.U
  val outAccess = wb.addr === 4.U
  val oebAccess = wb.addr === 8.U

  // input with two FFs to contain meta stability
  val syncedInput = RegNext(RegNext(io.in))

  // wishbone bus response logic
  wb.ack := ackReg
  wb.dout := MuxCase(0.U, Seq(
    inAccess -> syncedInput,
    outAccess -> outReg,
    oebAccess -> oebReg
  ))

  // wishbone write logic
  when(ackReg && wb.we) {
    when(outAccess) { outReg := wb.din(n - 1, 0) }
    when(oebAccess) { oebReg := wb.din(n - 1, 0) }
  }

  // Connect GPIO
  io.out := outReg
  io.oeb := oebReg
}
