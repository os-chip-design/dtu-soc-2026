from __future__ import annotations

from dataclasses import dataclass


THRESHOLD = 64
LAYER_SIZES = [8, 8, 4]
SPIKES_IN = 0xFF

# First layer: 8 inputs -> 8 hidden neurons. Every weight is +16.
FIRST_LAYER_WORDS = [0x10101010, 0x10101010] * 8

# Second layer: 8 hidden -> 4 outputs. Only hidden neuron 0 drives output 0 with +64.
SECOND_LAYER_WORDS = [0x00000040] + [0x00000000] * 7


@dataclass(frozen=True)
class TestVector:
    sram_words: list[int]
    spikes_in: int
    expected_spikes_out: int
    expected_config_word: int


def _signed_byte(value: int) -> int:
    return value - 0x100 if value & 0x80 else value


def _word_to_weights(word: int) -> list[int]:
    return [_signed_byte((word >> (lane * 8)) & 0xFF) for lane in range(4)]


def _run_layer(spikes: list[int], n_outputs: int, words: list[int]) -> list[int]:
    groups = (n_outputs + 3) // 4
    accum = [0] * n_outputs
    for input_idx, spike in enumerate(spikes):
        if not spike:
            continue
        for group_idx in range(groups):
            word = words[input_idx * groups + group_idx]
            for lane, weight in enumerate(_word_to_weights(word)):
                out_idx = group_idx * 4 + lane
                if out_idx < n_outputs:
                    accum[out_idx] += weight
    return [1 if value >= THRESHOLD else 0 for value in accum]


def _pack_bits(bits: list[int]) -> int:
    return sum((bit & 1) << idx for idx, bit in enumerate(bits))


def _config_word() -> int:
    n_layers = len(LAYER_SIZES) - 1
    n_inputs = LAYER_SIZES[0]
    n_outputs = LAYER_SIZES[-1]
    return (n_outputs << 12) | (n_inputs << 4) | n_layers


def build_test_vector() -> TestVector:
    spikes = [(SPIKES_IN >> idx) & 1 for idx in range(LAYER_SIZES[0])]
    hidden = _run_layer(spikes, LAYER_SIZES[1], FIRST_LAYER_WORDS)
    outputs = _run_layer(hidden, LAYER_SIZES[2], SECOND_LAYER_WORDS)

    sram_words = [len(LAYER_SIZES) - 1, *LAYER_SIZES, *FIRST_LAYER_WORDS, *SECOND_LAYER_WORDS]

    return TestVector(
        sram_words=sram_words,
        spikes_in=SPIKES_IN,
        expected_spikes_out=_pack_bits(outputs),
        expected_config_word=_config_word(),
    )


TEST_VECTOR = build_test_vector()
