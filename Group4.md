# Group 4 — Hardware-accelerated ray tracing

This subsystem is the Group 4 contribution to the DTU SoC 2026 tapeout: a small
ray-tracing accelerator and pixel-streaming peripheral that lives next to the
shared Wildcat RV32 core inside the Caravel user project.

## Goal

Render a simple 3D scene (one sphere on a checkerboard floor, with reflection)
in real hardware, fast enough that a software loop on the on-chip CPU would not
be able to keep up. The CPU configures the scene through MMIO registers, asks
the accelerator to render a frame, and then either drains the pixel bytes
through a dedicated UART pin or reads them back as MMIO. The output is an 8-bit
grayscale raster stream — what the receiving end does with it (display, save to
file, ASCII-fy on the host) is left to software.

## High-level design

The block sits at base address `0xf005_0000` on the Wildcat dmem bus. From the
software side it is just a set of memory-mapped registers; internally it is
three pieces stacked on top of each other:

```
        CPU (Wildcat ThreeCats)                pin 6 (rayTx UART)
               │ dmem bus                              ▲
               ▼                                       │
    ┌─────────────────────────────────────────────────────────────┐
    │ RayTracerController        (MMIO front-end)                 │
    │   camera / sphere / cols / rows / scale / start / mode regs │
    │                                                             │
    │   ┌──────────────────────┐     ┌──────────────────────────┐ │
    │   │ RayTracerAccelerator │ ──► │ PixelFifo  (1 KB SRAM)   │ │
    │   │  per-pixel FSM       │     │  byte FIFO, sync read    │ │
    │   │  1× sqrt, 2× divider │     └──────────────────────────┘ │
    │   └──────────────────────┘              │                   │
    │                                         ▼                   │
    │                          drain mux ── byteOut / 0x000C read │
    └─────────────────────────────────────────────────────────────┘
```

### Memory map (offsets from `0xf005_0000`)

| Offset  | Access | Field                                           |
|---------|--------|-------------------------------------------------|
| `0x0000`| W      | `start` (write 1 to launch a frame)             |
| `0x0004`| R      | `busy`                                          |
| `0x0008`| R      | FIFO `notEmpty`                                 |
| `0x000C`| R      | next pixel byte (only valid in MMIO drain mode) |
| `0x0010`| RW     | drain mode (0 = UART, 1 = MMIO via `0x000C`)    |
| `0x1000`–`0x1014` | W | CamX/Y/Z, SphereX/Y/Z (Q16.16)             |
| `0x1018`/`0x101C` | W | Cols / Rows (13-bit each)                  |
| `0x1020`/`0x1024` | W | ScaleX / ScaleY (Q16.16)                   |

Defaults at reset reproduce the original 32×32 demo scene (cam `(0, 1, −3)`,
sphere `(0, 1, 0)`, scale `1/24`), so simple firmware can just write `1` to
`0x0000` and start draining.

### Integration with CpuTop

The controller is mounted under a combined reset (`reset || io.cpu_reset`) so
that the firmware's "hold CPU in reset, load IMEM over wishbone" sequence also
keeps the accelerator from spuriously starting on random pre-reset state. The
controller's `rdData`/`ack` are registered one cycle in `CpuTop.scala` to
match Wildcat's "ack on the cycle after `rd`/`wr`" expectation. The
`BufferedTx` that turns `byteOut` into the GPIO-pin-6 UART line lives in
`CpuTop`, not in the controller — the controller only emits a `Decoupled`
byte stream.

## How a frame is rendered

Per-pixel work goes through an 11-state FSM (`psInitRender → psRowInit →
psWaitRowDiv → psSetup → psWaitSqrt → psWaitDiv1 → psComputeRefl →
psStartDiv2 → psWaitDiv2 → psFinishRefl → psWrite`) that time-multiplexes one
`IterSqrt` and two `SignedIterDivider` units. The data path is fixed-point
Q16.16 throughout, with internal divider/sqrt widths of 48 bits so the
quotient and root come back in Q16.16 after the appropriate shift.

For each pixel the accelerator:

1. Computes the ray direction `(dx, dy, 1)` from the column/row offsets
   relative to the screen center, multiplied by the per-frame scale.
2. Tests intersection with the (radius-1) sphere using the registered
   per-render scalars `oc = cam − sphere` and `c = |oc|² − 1`. Solves the
   quadratic with one shared `IterSqrt` and one divide for `t_sphere`.
3. If the sphere is hit, computes the reflected ray direction and, when it
   points downward, traces it to the floor with a second divide. The hit
   point's parity in floor coordinates picks one of two grayscale shades.
4. If the sphere is missed, the primary ray falls back to a horizon test
   against the floor — the floor row's `t_floor` is precomputed once per
   row in `psRowInit` (one divide amortised across the whole row).
5. Drops the resulting 8-bit byte into the `PixelFifo` and advances.

Camera, sphere, resolution and pixel scale are all sampled at `psInitRender`
and held for the full frame, so software can change the scene between frames
without racing the renderer.

The 6-entry grayscale palette in `RayTracerAccelerator.scala`
(`charSky`, `charFloor*`, `charSphere*`) is a placeholder — the byte values
were chosen so that ASCII downsampling on the host still produces a readable
image, but they are not load-bearing for the hardware.

### Pixel FIFO

`PixelFifo` is a 1 KB byte FIFO backed by the `sky130_sram_1kbyte_1rw1r_32x256_8`
macro, with byte-mask writes packing four pixels into each 32-bit word and a
small read pipeline (sFetch → sLatch → sValid) that hides the SRAM's sync-read
latency. It is bigger than strictly necessary, but it reuses a macro that
already has a sim model and decouples the FSM from whatever drains bytes
downstream.

## Testing

### Chisel unit tests (`src/test/scala/raytracer/`)

Run with `sbt test`, or scoped to one suite via
`sbt 'testOnly raytracer.<Spec>'`.

`SignedIterDividerSpec`
- Small positives at radix-2 (`K=1`) and radix-16 (`K=4`).
- All four sign combinations on `K=4`.
- Back-to-back reusability — one divider, many divisions in a row.
- Other valid radices: `K=2, 6, 8, 12`.
- Operands sized like the raytracer's widened Q16.16 inputs (the shape the
  FSM actually feeds).
- Random sweep against a `BigInt` reference.
- `busy` stays high during the operation and falls exactly once.

`IterSqrtSpec`
- Perfect squares 0..10 at `K=1`.
- `K=4` on small values, full 48-bit range, and the
  `disc << 16` shape the FSM uses.
- Reusability across back-to-back sqrts.
- Other unroll factors (`K=2, 6`).
- `busy` stays high for exactly N cycles.
- Random sweep vs. a `BigInt.sqrt` reference.

`RayTracerAcceleratorSpec`
- 32×32 hardware output matches a bit-exact Q16.16 software reference at the
  default camera, an offset camera `(0.5, 1, −3)`, an offset sphere
  `(1, 1, 0)`, and at a smaller 16×16 resolution. These four cases
  collectively exercise every runtime-configurable input.
- Re-pulsing `start` reliably re-runs the scene from cell 0.
- Two "dump" tests render to ASCII for visual inspection (32×32 hardware
  output and a high-resolution 1920×1080 pure-Scala reference).

`RayTracerControllerSpec`
- Reset values (`busy=0`, `notEmpty=0`, `drainMode=0`).
- Drain-mode toggle round-trips through MMIO.
- `start` pulse asserts `busy`.
- Full MMIO drain captures all 32×32 pixels.
- UART-drain path: `byteOut` streams every pixel when the consumer is ready.
- Writing cols/rows/scale via MMIO produces exactly 256 pixels at 16×16.
- Reading `0x000C` returns 0 while UART drain mode is active (mode-gating).

### Cocotb integration tests (`verilog/dv/cocotb/user_proj_tests/`)

These exercise the hardened user project — Wildcat boots a small RISC-V
program loaded over Caravel's wishbone, hits the controller through MMIO,
and signals success by writing `0xCAFE_xxxx` to the comm register. Run
individually with `cf verify <test_name>`.

| Test | What it covers |
|------|----------------|
| `raytracer_read_test` | Read-protocol coverage. Reads `busy`, `notEmpty`, `drainMode` at reset, expects all three to be 0. |
| `raytracer_write_test` | Write-protocol coverage. Round-trips `drainMode` (the only RW register) and writes patterns to all 11 write-only registers, asserting that every store is acknowledged. |
| `raytracer_mmio_test` | Full integration. Configures a 2×2 frame, pulses start, drains pixels via MMIO `0x000C`, and beacons a magic word with the drained pixel count. |
| `raytracer_uart_test` | Full integration via the dedicated UART. Configures and starts a render in UART drain mode; cocotb samples GPIO pin 6 and counts edges to confirm bytes shift out. |

## Standalone macro hardening (sky130A)

To get area numbers that aren't distorted by the rest of the SoC, the
`RayTracerController` is also hardened **alone** in its own macro
(`openlane/RayTracerController/`). It is not part of `user_project_wrapper`
and not run by CI — it's a one-off sandbox build for the report. Full
numbers live in [`openlane/RayTracerController/STATS.md`](openlane/RayTracerController/STATS.md);
the headlines:

- **Die area:** 1500 × 1500 µm = 2.25 mm² (1000 × 1000 fails placement).
- **Floor usage:** core area 2.20 mm², total instance area **1.11 mm²**
  (917 661 µm² stdcells + 190 713 µm² SRAM macro) — i.e. **50.4 %**
  overall instance utilization, 45.7 % stdcell-only.
- **Cell counts:** 97 038 std cells post-synth, 1 397 flip-flops, 1 SRAM
  macro, 184 178 total instances after PnR.
- **Timing (100 ns clock):** setup WS **+25.93 ns** / hold WS **+0.40 ns**
  at the slow corner — i.e. ~26 ns of clock-period headroom, so the area numbers are not inflated by
  timing-driven sizing.
- **Power:** ~13.3 mW total (4.9 mW internal, 8.4 mW switching) at the
  nominal corner.
- **Routing:** 4.31 m of wire, 0 DRC errors, 0 LVS errors, 0 design
  violations.

## Source map

All under `src/main/scala/raytracer/`:

| File | Role |
|------|------|
| `FixedPoint.scala` | Q16.16 helpers (`lit`, `mul`, `floorInt`). |
| `Divider.scala` | Multi-cycle signed iterative divider, radix-2^K. |
| `IterSqrt.scala` | Multi-cycle integer square root, radix-2^K. |
| `RayTracerAccelerator.scala` | 11-state per-pixel FSM and datapath. |
| `PixelFifo.scala` | 1 KB byte FIFO around the sky130 SRAM macro. |
| `RayTracerController.scala` | MMIO wrapper, drain-mode mux, register file. |

Tests live under `src/test/scala/raytracer/`; cocotb tests under
`verilog/dv/cocotb/user_proj_tests/raytracer_*_test/`.
