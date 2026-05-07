from __future__ import annotations

from pathlib import Path

from golden_model import TEST_VECTOR


HEADER_PATH = Path(__file__).with_name("snn_wishbone_test_vectors.h")


def format_word(word: int) -> str:
    return f"0x{word:08X}u"


def render_header() -> str:
    words = ",\n    ".join(format_word(word) for word in TEST_VECTOR.sram_words)
    return f"""#ifndef SNN_WISHBONE_TEST_VECTORS_H
#define SNN_WISHBONE_TEST_VECTORS_H

#include <stdint.h>

#define SNN_TEST_VECTOR_WORD_COUNT {len(TEST_VECTOR.sram_words)}
#define SNN_TEST_SPIKES_IN {format_word(TEST_VECTOR.spikes_in)}
#define SNN_EXPECTED_SPIKES_OUT {format_word(TEST_VECTOR.expected_spikes_out)}
#define SNN_EXPECTED_CONFIG_WORD {format_word(TEST_VECTOR.expected_config_word)}

static const uint32_t snn_test_vector_words[SNN_TEST_VECTOR_WORD_COUNT] = {{
    {words}
}};

#endif
"""


def main() -> None:
    HEADER_PATH.write_text(render_header())


if __name__ == "__main__":
    main()
