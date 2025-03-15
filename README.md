SimpleRoCC
=======================
A knock off of RoCC interface in Rocket-Chip generator. With this you can build a RoCC accelerator without extending on `LazyRoCC`. 

This project uses [playground](https://github.com/morphingmachines/playground.git) as a library. `playground` and this project directories should be at the same level, as shown below.  
```
  workspace
  |-- playground
  |-- SimpleRoCC
```
Make sure that you have a working [playground](https://github.com/morphingmachines/playground.git) project before proceeding further. Do not rename/modify `playground` directory structure.
## Clone the code
```sh
git clone --recursive git@github.com:morphingmachines/SimpleRoCC.git
```

## Generating Verilog

Verilog code can be generated from Chisel by using the `rtl` Makefile target.

```sh
make rtl
```

The output verilog files are generated in the `./generated_sv_dir` directory. 
