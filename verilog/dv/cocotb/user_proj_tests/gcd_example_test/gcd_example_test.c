// SPDX-FileCopyrightText: 2023 Efabless Corporation

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

//      http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <firmware_apis.h>

// address of the gcd module in words
#define GCD_ADDR (0x10000 >> 2) 

int gcd_input_is_ready() {
    return USER_readWord(GCD_ADDR + 1) & 0x2;
}

int gcd_output_is_valid() {
    return USER_readWord(GCD_ADDR + 1) & 0x1;
}

void gcd_write_input(short a, short b) {
    USER_writeWord(((int)a << 16) | (int)b, GCD_ADDR);
}

short gcd_read_output() {
    return (short)(USER_readWord(GCD_ADDR) & 0xFFFF);
}

void main(){
    // Enable managment gpio as output to use as indicator for finishing configuration  
    ManagmentGpio_outputEnable();
    ManagmentGpio_write(0);
    enableHkSpi(0); // disable housekeeping spi
    // configure all gpios as  user out then chenge gpios from 32 to 37 before loading this configurations
    GPIOs_configureAll(GPIO_MODE_USER_STD_OUT_MONITORED);
    
    GPIOs_loadConfigs(); // load the configuration 
    User_enableIF(); // this necessary when reading or writing between wishbone and user project if interface isn't enabled no ack would be recieve and the command will be stuck
    

    short a = 48;
    short b = 18;
    short gcd = 6;

    // wait until gcd input is ready
    while(!gcd_input_is_ready());
    // write input values
    gcd_write_input(a, b);
    // wait until output is valid
    while(!gcd_output_is_valid());
    // read output value
    short result = gcd_read_output();
    // check result
    if (result == gcd) {
        // indicate success by setting managment gpio high
        ManagmentGpio_write(1);
    } // else do nothing and let the testbench timeout

    return;
}