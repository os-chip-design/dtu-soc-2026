#include <firmware_apis.h>
#include <stdint.h>
#include "snn_wishbone_test_vectors.h"

#define SNN_BASE_WORD_ADDR   (0x800000 >> 2)
#define SNN_CTRL_WORD        (SNN_BASE_WORD_ADDR + 0)
#define SNN_SPIKES_IN_WORD   (SNN_BASE_WORD_ADDR + 1)
#define SNN_SPIKES_OUT_WORD  (SNN_BASE_WORD_ADDR + 2)
#define SNN_CONFIG_WORD      (SNN_BASE_WORD_ADDR + 3)
#define SNN_WEIGHT_BASE_WORD (SNN_BASE_WORD_ADDR + 4)

static void program_sram_window(void) {
    for (int i = 0; i < SNN_TEST_VECTOR_WORD_COUNT; ++i) {
        USER_writeWord(snn_test_vector_words[i], SNN_WEIGHT_BASE_WORD + i);
    }
}

static int wait_for_done(void) {
    for (int i = 0; i < 200; ++i) {
        if (USER_readWord(SNN_CTRL_WORD) & 0x2) {
            return 1;
        }
    }
    return 0;
}

void main() {
    ManagmentGpio_outputEnable();
    ManagmentGpio_write(0);
    enableHkSpi(0);
    User_enableIF();

    program_sram_window();

    USER_writeWord(SNN_TEST_SPIKES_IN, SNN_SPIKES_IN_WORD);
    USER_writeWord(0x1, SNN_CTRL_WORD);

    if (!wait_for_done()) {
        while (1);
    }

    if (USER_readWord(SNN_SPIKES_OUT_WORD) != SNN_EXPECTED_SPIKES_OUT) {
        while (1);
    }

    uint32_t config = USER_readWord(SNN_CONFIG_WORD);
    if (config != SNN_EXPECTED_CONFIG_WORD) {
        while (1);
    }

    ManagmentGpio_write(1);

    while (1);
}
