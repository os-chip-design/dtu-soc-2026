import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, Timer


async def write_port0(dut, address: int, data: int, mask: int = 0b1111):
    dut.csb0.value = 0
    dut.web0.value = 0
    dut.addr0.value = address
    dut.din0.value = data
    dut.wmask0.value = mask
    await RisingEdge(dut.clk0)
    dut.csb0.value = 1
    dut.web0.value = 1
    await RisingEdge(dut.clk0)


async def read_port0(dut, address: int) -> int:
    dut.csb0.value = 0
    dut.web0.value = 1
    dut.addr0.value = address
    await RisingEdge(dut.clk0)
    await RisingEdge(dut.clk0)
    value = int(dut.dout0.value)
    dut.csb0.value = 1
    return value


async def read_port1(dut, address: int) -> int:
    dut.csb1.value = 0
    dut.addr1.value = address
    await RisingEdge(dut.clk1)
    await RisingEdge(dut.clk1)
    value = int(dut.dout1.value)
    dut.csb1.value = 1
    return value


async def dual_port_write_read(dut, write_addr: int, write_data: int, read_addr: int):
    dut.csb0.value = 0
    dut.web0.value = 0
    dut.addr0.value = write_addr
    dut.din0.value = write_data
    dut.wmask0.value = 0b1111

    dut.csb1.value = 0
    dut.addr1.value = read_addr

    await RisingEdge(dut.clk0)
    await RisingEdge(dut.clk0)
    dut.csb0.value = 1
    dut.web0.value = 1

    await RisingEdge(dut.clk1)
    await RisingEdge(dut.clk1)
    value = int(dut.dout1.value)
    dut.csb1.value = 1
    return value


@cocotb.test()
async def ram_test(dut):
    cocotb.start_soon(Clock(dut.clk0, 10, units="ns").start())
    cocotb.start_soon(Clock(dut.clk1, 10, units="ns").start())

    dut.csb0.value = 1
    dut.web0.value = 1
    dut.csb1.value = 1
    dut.wmask0.value = 0b1111
    dut.addr0.value = 0
    dut.addr1.value = 0
    dut.din0.value = 0

    await Timer(20, units="ns")

    cocotb.log.info("[RAM TEST] Full memory sweep on Port0 and Port1")

    # Full memory write sweep
    for addr in range(256):
        data = ((addr & 0xFF) << 24) | ((~addr & 0xFF) << 16) | (addr << 8) | (~addr & 0xFF)
        await write_port0(dut, addr, data)

    # Readback on Port0
    for addr in range(256):
        expected = ((addr & 0xFF) << 24) | ((~addr & 0xFF) << 16) | (addr << 8) | (~addr & 0xFF)
        read_value = await read_port0(dut, addr)
        assert read_value == expected, f"Full sweep Port0 failed at {addr:#04x}: expected {expected:#010x}, got {read_value:#010x}"

    # Readback on Port1
    for addr in range(256):
        expected = ((addr & 0xFF) << 24) | ((~addr & 0xFF) << 16) | (addr << 8) | (~addr & 0xFF)
        read_value = await read_port1(dut, addr)
        assert read_value == expected, f"Full sweep Port1 failed at {addr:#04x}: expected {expected:#010x}, got {read_value:#010x}"

    cocotb.log.info("[RAM TEST] Dual-port integrity test")

    # Dual-port transaction integrity: Port0 writes while Port1 reads a different address
    for addr in range(16):
        write_addr = addr
        read_addr = (addr + 16) & 0xFF
        write_value = 0xA5A50000 | addr
        expected = ((read_addr & 0xFF) << 24) | ((~read_addr & 0xFF) << 16) | (read_addr << 8) | (~read_addr & 0xFF)
        read_value = await dual_port_write_read(dut, write_addr, write_value, read_addr)
        assert read_value == expected, f"Dual-port integrity failed at write {write_addr:#04x}, read {read_addr:#04x}: expected {expected:#010x}, got {read_value:#010x}"

    cocotb.log.info("[RAM TEST] Write mask accuracy test")

    await write_port0(dut, 0x5A, 0xDEADBEEF, 0b1111)
    await write_port0(dut, 0x5A, 0x12345678, 0b0110)
    read_value = await read_port0(dut, 0x5A)
    assert read_value == 0xDE3456EF, f"Write mask failed: expected 0xDE3456EF, got {read_value:#010x}"

    cocotb.log.info("[RAM TEST] Collision behavior test")

    old_value = await read_port1(dut, 0xC0)
    new_value = 0xFEEDF00D
    dut.csb0.value = 0
    dut.web0.value = 0
    dut.addr0.value = 0xC0
    dut.din0.value = new_value
    dut.wmask0.value = 0b1111
    dut.csb1.value = 0
    dut.addr1.value = 0xC0

    await RisingEdge(dut.clk0)
    await RisingEdge(dut.clk0)
    collision_read = int(dut.dout1.value)
    dut.csb0.value = 1
    dut.web0.value = 1
    dut.csb1.value = 1

    assert collision_read in (old_value, new_value), (
        f"Collision behavior failed: read {collision_read:#010x}, expected old {old_value:#010x} or new {new_value:#010x}"
    )

    await Timer(20, units="ns")
    cocotb.log.info("[RAM TEST] Completed successfully")
