import cocotb
from cocotb.clock import Clock
from cocotb.triggers import ClockCycles, RisingEdge


WB_CTRL = 0x00
WB_SPIKES_IN = 0x04
WB_SPIKES_OUT = 0x08
WB_CONFIG = 0x0C
WB_WEIGHT_BASE = 0x10


async def start_clock(dut, period=2):
    cocotb_clock = Clock(dut.clock, period, unit="step")
    return cocotb.start_soon(cocotb_clock.start())


async def reset_dut(dut):
    dut.reset.value = 1
    dut.wb_cyc.value = 0
    dut.wb_stb.value = 0
    dut.wb_we.value = 0
    dut.wb_addr.value = 0
    dut.wb_din.value = 0
    dut.wb_sel.value = 0xF
    await ClockCycles(dut.clock, 2)
    dut.reset.value = 0
    await RisingEdge(dut.clock)


async def wb_write(dut, addr, data):
    dut.wb_addr.value = addr
    dut.wb_din.value = data
    dut.wb_sel.value = 0xF
    dut.wb_we.value = 1
    dut.wb_cyc.value = 1
    dut.wb_stb.value = 1
    await RisingEdge(dut.clock)
    dut.wb_cyc.value = 0
    dut.wb_stb.value = 0
    dut.wb_we.value = 0
    await RisingEdge(dut.clock)


async def wb_read(dut, addr):
    dut.wb_addr.value = addr
    dut.wb_we.value = 0
    dut.wb_cyc.value = 1
    dut.wb_stb.value = 1
    await RisingEdge(dut.clock)
    value = int(dut.wb_dout.value)
    dut.wb_cyc.value = 0
    dut.wb_stb.value = 0
    await RisingEdge(dut.clock)
    return value


async def wb_read_sram(dut, addr):
    dut.wb_addr.value = addr
    dut.wb_we.value = 0
    dut.wb_cyc.value = 1
    dut.wb_stb.value = 1
    await RisingEdge(dut.clock)
    await RisingEdge(dut.clock)
    value = int(dut.wb_dout.value)
    dut.wb_cyc.value = 0
    dut.wb_stb.value = 0
    await RisingEdge(dut.clock)
    return value


async def wait_done(dut, max_cycles):
    for _ in range(max_cycles):
        status = await wb_read(dut, WB_CTRL)
        if status & 0x2:
            return status
    raise AssertionError(f"inference did not complete within {max_cycles} polling cycles")


def weight_addr(word_index):
    return WB_WEIGHT_BASE + word_index * 4


def flex_header_words(layer_sizes):
    return len(layer_sizes) + 1


async def program_flex_header(dut, layer_sizes):
    await wb_write(dut, weight_addr(0), len(layer_sizes) - 1)
    for idx, size in enumerate(layer_sizes, start=1):
        await wb_write(dut, weight_addr(idx), size)
