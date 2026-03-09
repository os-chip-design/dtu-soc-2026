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

// address of the gpio module in words
#define GPIO_ADDR (0x00000 >> 2) 

void gpio_set_oeb(int value) {
    USER_writeWord(value, GPIO_ADDR + 2);
}

void gpio_set_output(int value) {
    USER_writeWord(value, GPIO_ADDR + 1);
}

int gpio_get_input() {
    return USER_readWord(GPIO_ADDR);
}



void main(){
    // Enable managment gpio as output to use as indicator for finishing configuration  
    ManagmentGpio_outputEnable();
    ManagmentGpio_write(0);
    enableHkSpi(0); // disable housekeeping spi

    // TODO: configure all gpios as GPIO_MODE_USER_STD_INPUT_PULLDOWN and load the configuration

    // TODO: enable wishbone interface

    int res = 0;
    // TODO: wait until the value of the input is 0xAA

    if (res == 0xAA) {
        ManagmentGpio_write(1); // signal pass
    }
    
    return;
}