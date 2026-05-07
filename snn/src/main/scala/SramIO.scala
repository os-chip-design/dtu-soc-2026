import chisel3._
import chisel3.util._

// Generic single-port SRAM interface matching OpenRAM conventions.
// Active-low control signals (csb, web) to match OpenRAM macros directly.
// Word width = nOut * weightWidth  (one row of weights per address).
// Depth = nIn  (one address per input neuron).
class SramIO(addrWidth: Int, dataWidth: Int) extends Bundle {
  val clk  = Output(Clock())         // clock output to SRAM macro
  val csb  = Output(Bool())          // chip select, active low
  val web  = Output(Bool())          // write enable, active low
  val addr = Output(UInt(addrWidth.W))
  val din  = Output(UInt(dataWidth.W))
  val dout = Input(UInt(dataWidth.W)) // read data (available 1 cycle after request)
}
