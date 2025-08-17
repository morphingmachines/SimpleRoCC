project = simpleRoCC

TARGET ?= RV32

# Toolchains and tools
MILL = ./../playground/mill

-include ./../playground/Makefile.include

# Targets
rtl:check-firtool ## Generates Verilog code from Chisel sources (output to ./generated_sv_dir)
	$(MILL) $(project).runMain $(project).Main $(TARGET)
