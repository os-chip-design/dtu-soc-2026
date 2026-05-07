import chiseltest._
import org.scalatest.freespec.AnyFreeSpec

class MultiLayerSnnFlexSpec extends AnyFreeSpec with ChiselScalatestTester {
  "MultiLayerSnnFlex" - {
    "should elaborate with [8, 16, 4]" in {
      test(new MultiLayerSnnFlex(capacityLayerSizes = Seq(8, 16, 4))) { dut =>
        dut.clock.step(1)
      }
    }

    "should elaborate with [8, 4]" in {
      test(new MultiLayerSnnFlex(capacityLayerSizes = Seq(8, 4))) { dut =>
        dut.clock.step(1)
      }
    }
  }
}
