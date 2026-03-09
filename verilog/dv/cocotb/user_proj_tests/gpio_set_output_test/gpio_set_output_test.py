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


@cocotb.test()
@report_test
async def gpio_set_output_test(dut):
    caravelEnv = await test_configure(dut,timeout_cycles=27649)
    await caravelEnv.wait_mgmt_gpio(1) # wait for caravel to signal that the output is set

    out = caravelEnv.monitor_gpio(15,8).binstr
    assert out == '01100110', f"Expected GPIO output to be 0x66, but got {out}"
    
    cocotb.log.info(f"[TEST] pass")
