# SPDX-FileCopyrightText: 2023 Efabless Corporation

# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at

#      http://www.apache.org/licenses/LICENSE-2.0

# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# SPDX-License-Identifier: Apache-2.0


from caravel_cocotb.caravel_interfaces import test_configure
from caravel_cocotb.caravel_interfaces import report_test
import cocotb



async def wait_for_gpio(caravelEnv, value):
    while True:
        gpio_value = caravelEnv.monitor_gpio(11,8)
        if gpio_value == value:
            break
        await cocotb.triggers.ClockCycles(caravelEnv.clk, 1)

@cocotb.test()
@report_test
async def gpio_bidir_test(dut):
    caravelEnv = await test_configure(dut,timeout_cycles=50000)
    caravelEnv.drive_gpio_in((15,12), 0xF) # set start value for the input bits
    cocotb.log.info(f"[TEST] Start GPIO bidirectional test")  
    # wait for start of sending
    await caravelEnv.release_csb()

    for i in range(16):
        await wait_for_gpio(caravelEnv, i) # wait for the lower 4 bits to be equal to i
        cocotb.log.info(f"[TEST] Received GPIO value {i}") 
        caravelEnv.drive_gpio_in((15,12), i) # set the upper 4 bits to the value of i

    cocotb.log.info(f"[TEST] Waiting GPIO to signal pass")  
    await caravelEnv.wait_mgmt_gpio(1)
    cocotb.log.info(f"[TEST] Received GPIO pass signal")
