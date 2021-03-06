// See LICENSE for license details.

#ifndef __FPGA_MEMORY_MODEL_H
#define __FPGA_MEMORY_MODEL_H

#include <unordered_map>
#include <fstream>

#include "fpga_model.h"

// Driver for the midas memory model

class FpgaMemoryModel: public FpgaModel
{
public:
  FpgaMemoryModel(simif_t* s, AddressMap addr_map);
  virtual ~FpgaMemoryModel() { }
  void init(int argc, char** argv);
  void profile();
  void finish();

private:
  // Saves a map of register names to settings
  std::unordered_map<std::string, uint32_t> model_configuration;
};

#endif // __FPGA_MEMORY_MODEL_H
