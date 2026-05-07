from caravel_cocotb.caravel_interfaces import report_test, test_configure
import cocotb
from user_proj_tests.snn_wishbone_test.golden_model import TEST_VECTOR


@cocotb.test()
@report_test
async def snn_wishbone_test(dut):
    caravelEnv = await test_configure(dut, timeout_cycles=200000)

    cocotb.log.info("[TEST] Start SNN Wishbone test")
    cocotb.log.info(
        f"[TEST] Python golden model expects spikes_out=0x{TEST_VECTOR.expected_spikes_out:X} "
        f"and config=0x{TEST_VECTOR.expected_config_word:X}"
    )
    await caravelEnv.release_csb()
    cocotb.log.info("[TEST] Waiting for SNN test firmware to signal pass")
    await caravelEnv.wait_mgmt_gpio(1)
    cocotb.log.info("[TEST] SNN test firmware reported success")
