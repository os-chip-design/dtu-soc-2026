# test_wb_imem.py
import cocotb
from cocotb.triggers import RisingEdge, Timer
from cocotb.result import TestFailure
from cocotb.clock import Clock

@cocotb.test()
async def mem_test(dut):
    """Test Wishbone instruction memory read/write at base address 0x30000000"""

    # Create a 10ns period clock on dut.wb_clk (assuming you have a clk)
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())

    # Reset DUT
    dut.wb.cyc.value = 0
    dut.wb.stb.value = 0
    dut.wb.we.value = 0
    dut.wb.addr.value = 0
    dut.wb.wrData.value = 0
    dut.wb.sel.value = 0
    dut.wb.rdData.value = 0
    dut.wb.ack.value = 0

    await RisingEdge(dut.clk)
    await RisingEdge(dut.clk)

    # --- Write 0xf0f0f0f0 to 0x30000000 ---
    dut._log.info("Writing 0xf0f0f0f0 to 0x30000000")
    dut.wb.addr.value = 0x30000000
    dut.wb.wrData.value = 0xf0f0f0f0
    dut.wb.we.value = 1
    dut.wb.cyc.value = 1
    dut.wb.stb.value = 1
    dut.wb.sel.value = 0xF  # 4-byte write

    # Wait one cycle for memory to acknowledge
    await RisingEdge(dut.clk)
    if dut.wb.ack.value != 1:
        raise TestFailure("Memory did not acknowledge write")

    # Deassert write signals
    dut.wb.we.value = 0
    dut.wb.stb.value = 0
    dut.wb.cyc.value = 0

    await RisingEdge(dut.clk)

    # --- Read back from 0x30000000 ---
    dut._log.info("Reading back from 0x30000000")
    dut.wb.addr.value = 0x30000000
    dut.wb.we.value = 0
    dut.wb.cyc.value = 1
    dut.wb.stb.value = 1
    dut.wb.sel.value = 0xF  # full 32-bit read

    await RisingEdge(dut.clk)

    if dut.wb.ack.value != 1:
        raise TestFailure("Memory did not acknowledge read")

    read_value = int(dut.wb.rdData.value)
    dut._log.info(f"Read value: 0x{read_value:08X}")

    if read_value != 0xf0f0f0f0:
        raise TestFailure(f"Memory read returned 0x{read_value:08X}, expected 0xf0f0f0f0")
    else:
        dut._log.info("Test passed!")