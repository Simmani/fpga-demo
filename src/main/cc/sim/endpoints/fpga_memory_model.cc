// See LICENSE for license details.

#include <iostream>
#include <algorithm>
#include <exception>
#include <stdio.h>

#include "fpga_memory_model.h"

FpgaMemoryModel::FpgaMemoryModel(
    simif_t* sim, AddressMap addr_map)
  : FpgaModel(sim, addr_map) {
}

void FpgaMemoryModel::profile() {
}

void FpgaMemoryModel::init(int argc, char** argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  for (auto &arg: args) {
    if(arg.find("+mm_") == 0) {
      auto sub_arg = std::string(arg.c_str() + 4);
      size_t delimit_idx = sub_arg.find_first_of("=");
      std::string key = sub_arg.substr(0, delimit_idx).c_str();
      int value = std::stoi(sub_arg.substr(delimit_idx+1).c_str());
      model_configuration[key] = value;
    }
  }

  for (auto &pair: addr_map.w_registers) {
    auto value_it = model_configuration.find(pair.first);
    if (value_it != model_configuration.end()) {
      write(pair.second, value_it->second);
    } else {
      char buf[100];
      sprintf(buf, "No value provided for configuration register: %s", pair.first.c_str());
      throw std::runtime_error(buf);
    }
  }
}

void FpgaMemoryModel::finish() {
#ifdef MEMMODEL_0
#define readw(x) \
  ((size_t)read(MEMMODEL_0_ ## x ## _HIGH) << 32) | \
  ((size_t)read(MEMMODEL_0_ ## x ## _LOW))
 size_t llc_reads = readw(LLC_READS);
 size_t llc_writes = readw(LLC_WRITES);
 size_t misses = readw(MISSES);
 size_t same_row_reads = readw(SAME_ROW_READS);
 size_t diff_row_reads = readw(DIFF_ROW_READS);
 size_t same_row_writes = readw(SAME_ROW_WRITES);
 size_t diff_row_writes = readw(DIFF_ROW_WRITES);

 fprintf(stderr, "Memory Model Stats\n");
 fprintf(stderr, " - LLC reads: %zu\n", llc_reads);
 fprintf(stderr, " - LLC writes: %zu\n", llc_writes);
 fprintf(stderr, " - Misses: %zu\n", misses);
 fprintf(stderr, " - Same row reads: %zu\n", same_row_reads);
 fprintf(stderr, " - Diff row reads: %zu\n", diff_row_reads);
 fprintf(stderr, " - Same row writes: %zu\n", same_row_writes);
 fprintf(stderr, " - Diff row writes: %zu\n", diff_row_writes);
 assert(misses == same_row_reads + same_row_writes +
                  diff_row_reads + diff_row_writes);
#undef readw
#endif
}
