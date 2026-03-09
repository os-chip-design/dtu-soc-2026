import chisel3._
import chisel3.util.Decoupled

/**
  * Wishbone peripheral that computes the GCD of two n-bit numbers, where n <= 16.
  * The peripheral has two registers:
  * - data register at address 0x0 (read/write) 
  *   - write: lower 16 bits are the first input, upper 16 bits are the second input
  *   - read: lower 16 bits are the GCD result
  * - status register at address 0x4 (read-only)
  *   - bit 0: output valid (1 = GCD result is valid and can be read from the data register)
  *   - bit 1: input ready (1 = peripheral is ready to accept new inputs on the data register)
  * 
  * @param width bit width of the unit (must be <= 16)
  */
class WishboneGcd(width: Int) extends Module {

  require(width <= 16, "GCD width cannot exceed 16 bits")

  // We only need 3 bits to address the 2 registers (input/output and status)
  val WB_ADDR_WIDTH = 3

  val wb = IO(new wishbone.WishboneIO(addrWidth = WB_ADDR_WIDTH, dataWidth = 32))

  val dataAccess = wb.addr === 0.U
  val statusAccess = wb.addr === 4.U

  val ackReg = RegInit(0.B)
  when(ackReg) {
    ackReg := 0.B
  }.elsewhen(wb.cyc && wb.stb) {
    ackReg := 1.B
  }

  val gcd = Module(new DecoupledGcd(width))

  gcd.input.valid := ackReg && wb.we && dataAccess
  gcd.input.bits.value1 := wb.din(15, 0)
  gcd.input.bits.value2 := wb.din(31, 16)

  gcd.output.ready := ackReg && !wb.we && dataAccess

  
  wb.ack := ackReg && Mux(statusAccess, 1.B, Mux(wb.we, gcd.input.ready, gcd.output.valid))
  wb.dout := Mux(statusAccess, gcd.input.ready ## gcd.output.valid,gcd.output.bits.gcd)

}


// Taken from https://github.com/chipsalliance/chisel-template/blob/main/src/main/scala/gcd/DecoupledGCD.scala

class GcdInputBundle(val w: Int) extends Bundle {
  val value1 = UInt(w.W)
  val value2 = UInt(w.W)
}

class GcdOutputBundle(val w: Int) extends Bundle {
  val value1 = UInt(w.W)
  val value2 = UInt(w.W)
  val gcd    = UInt(w.W)
}

/**
  * Compute Gcd using subtraction method.
  * Subtracts the smaller from the larger until register y is zero.
  * value input register x is then the Gcd.
  * Unless first input is zero then the Gcd is y.
  * Can handle stalls on the producer or consumer side
  */
class DecoupledGcd(width: Int) extends Module {
  val input = IO(Flipped(Decoupled(new GcdInputBundle(width))))
  val output = IO(Decoupled(new GcdOutputBundle(width)))

  val xInitial    = Reg(UInt())
  val yInitial    = Reg(UInt())
  val x           = Reg(UInt())
  val y           = Reg(UInt())
  val busy        = RegInit(false.B)
  val resultValid = RegInit(false.B)

  input.ready := ! busy
  output.valid := resultValid
  output.bits := DontCare

  when(busy)  {
    when(x > y) {
      x := x - y
    }.otherwise {
      y := y - x
    }
    when(x === 0.U || y === 0.U) {
      when(x === 0.U) {
        output.bits.gcd := y
      }.otherwise {
        output.bits.gcd := x
      }

      output.bits.value1 := xInitial
      output.bits.value2 := yInitial
      resultValid := true.B

      when(output.ready && resultValid) {
        busy := false.B
        resultValid := false.B
      }
    }
  }.otherwise {
    when(input.valid) {
      val bundle = input.deq()
      x := bundle.value1
      y := bundle.value2
      xInitial := bundle.value1
      yInitial := bundle.value2
      busy := true.B
    }
  }
}
