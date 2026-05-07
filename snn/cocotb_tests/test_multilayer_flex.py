import os
from pathlib import Path

import cocotb
from cocotb_tools.runner import get_runner

from common import (
    WB_CONFIG,
    WB_CTRL,
    WB_SPIKES_IN,
    WB_SPIKES_OUT,
    flex_header_words,
    program_flex_header,
    reset_dut,
    start_clock,
    wait_done,
    wb_read,
    wb_write,
    weight_addr,
)


@cocotb.test()
async def multilayer_flex_subgroup_inference(dut):
    await start_clock(dut)
    await reset_dut(dut)

    layer_sizes = [8, 8, 4]
    await program_flex_header(dut, layer_sizes)
    header_words = flex_header_words(layer_sizes)

    for i in range(8):
        await wb_write(dut, weight_addr(header_words + i * 2), 0x10101010)
        await wb_write(dut, weight_addr(header_words + i * 2 + 1), 0x10101010)

    base1 = header_words + 16
    for i in range(8):
        await wb_write(dut, weight_addr(base1 + i), 0x00000040 if i == 0 else 0x00000000)

    await wb_write(dut, WB_SPIKES_IN, 0xFF)
    await wb_write(dut, WB_CTRL, 0x1)
    await wait_done(dut, max_cycles=200)

    spikes_out = await wb_read(dut, WB_SPIKES_OUT)
    assert spikes_out == 0x1

    config = await wb_read(dut, WB_CONFIG)
    assert (config & 0xF) == 2
    assert ((config >> 4) & 0xFF) == 8
    assert ((config >> 12) & 0xFF) == 4


@cocotb.test()
async def multilayer_flex_second_subgroup_addressing(dut):
    await start_clock(dut)
    await reset_dut(dut)

    layer_sizes = [8, 8, 4]
    await program_flex_header(dut, layer_sizes)
    header_words = flex_header_words(layer_sizes)

    for i in range(8):
        await wb_write(dut, weight_addr(header_words + i * 2), 0x00000000)
        await wb_write(dut, weight_addr(header_words + i * 2 + 1), 0x10101010)

    base1 = header_words + 16
    for i in range(8):
        if i == 4:
            word = 0x00000040
        elif i == 7:
            word = 0x00004000
        else:
            word = 0x00000000
        await wb_write(dut, weight_addr(base1 + i), word)

    await wb_write(dut, WB_SPIKES_IN, 0xFF)
    await wb_write(dut, WB_CTRL, 0x1)
    await wait_done(dut, max_cycles=200)

    spikes_out = await wb_read(dut, WB_SPIKES_OUT)
    assert spikes_out == 0x3


@cocotb.test()
async def multilayer_flex_clears_subthreshold_accumulators_between_runs(dut):
    await start_clock(dut)
    await reset_dut(dut)

    layer_sizes = [8, 4]
    await program_flex_header(dut, layer_sizes)
    header_words = flex_header_words(layer_sizes)

    for i in range(8):
        await wb_write(dut, weight_addr(header_words + i), 0x00000008 if i == 0 else 0x00000000)

    for run_idx in range(10):
        await wb_write(dut, WB_SPIKES_IN, 0x01)
        await wb_write(dut, WB_CTRL, 0x1)
        await wait_done(dut, max_cycles=100)
        spikes_out = await wb_read(dut, WB_SPIKES_OUT)
        assert spikes_out == 0x0, f"run {run_idx + 1} should remain subthreshold because the flex core clears accumulators on every start"


def test_multilayer_flex():
    project_root = Path(__file__).resolve().parents[1]
    rtl = project_root / "verilog" / "rtl" / "WishboneMultiLayerSnnFlexTop.sv"
    sram_model = project_root / "src" / "main" / "resources" / "sky130_sram_1kbyte_1rw1r_32x256_8.v"
    test_dir = Path(__file__).resolve().parent
    build_root = Path(os.getenv("SNN_COCOTB_BUILD_ROOT", project_root / "sim_build"))
    runner = get_runner(os.getenv("SIM", "icarus"))
    runner.build(
        sources=[rtl, sram_model],
        hdl_toplevel="WishboneMultiLayerSnnFlexTop",
        build_dir=build_root / "multilayer_flex",
        always=True,
    )
    runner.test(
        hdl_toplevel="WishboneMultiLayerSnnFlexTop",
        test_module=Path(__file__).stem,
        test_dir=test_dir,
        extra_env={"PYTHONPATH": str(test_dir)},
    )
