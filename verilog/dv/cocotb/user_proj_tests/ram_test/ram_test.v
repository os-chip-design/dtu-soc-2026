module ram_test_tb;

    reg clk0 = 0;
    reg clk1 = 0;
    reg csb0 = 1;
    reg csb1 = 1;
    reg web0 = 1;
    reg [3:0] wmask0 = 4'b1111;
    reg [7:0] addr0 = 0;
    reg [7:0] addr1 = 0;
    reg [31:0] din0 = 0;
    wire [31:0] dout0;
    wire [31:0] dout1;

    sky130_sram_1kbyte_1rw1r_32x256_8 dut (
        .clk0(clk0),
        .csb0(csb0),
        .web0(web0),
        .wmask0(wmask0),
        .addr0(addr0),
        .din0(din0),
        .dout0(dout0),
        .clk1(clk1),
        .csb1(csb1),
        .addr1(addr1),
        .dout1(dout1)
    );

    always #5 clk0 = ~clk0;
    always #5 clk1 = ~clk1;

endmodule
