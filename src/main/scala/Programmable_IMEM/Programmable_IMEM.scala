import chisel3._
import chisel3.util._
import wishbone._

class Programmable_IMEM(val depth: Int) extends Module {
    val io = IO(new Bundle {
    val wb = Flipped(new WishboneIO(32)) // Memory is slave
  })

  val BASE_ADDR = "h30000000".U(32.W)

  // 32-bit wide memory
  val mem = SyncReadMem(depth, UInt(32.W))

  // Default outputs
  io.wb.setDefaultsFlipped() // rdData = 0, ack = false

  // Address calculation
  val addr_offset = io.wb.addr - BASE_ADDR
  val word_addr   = addr_offset(31, 2) // Word addressing

  when(io.wb.cyc && io.wb.stb) {
    io.wb.ack := true.B // Always acknowledge immediately

    when(io.wb.we) {
      // Write
      when(word_addr < depth.U) {
        val wdata = Wire(UInt(32.W))
        wdata := io.wb.wrData

        // Handle sel (byte enables)
        for (i <- 0 until 4) {
          when(io.wb.sel(i)) {
            val mask = (0xFF.U << (8 * i)).asUInt
            val old = mem.read(word_addr, true.B)
            mem.write(word_addr, (old & ~mask) | (wdata & mask))
          }
        }
      }
    }.otherwise {
      // Read
      when(word_addr < depth.U) {
        io.wb.rdData := mem.read(word_addr, true.B)
      }
    }
  }
}