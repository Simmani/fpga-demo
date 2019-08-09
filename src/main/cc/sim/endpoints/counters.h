#ifndef __COUNTERS_H
#define __COUNTERS_H

#include "endpoint.h"
#include <string>
#include <vector>
#include <fstream>

#ifdef ENABLE_COUNTERS
class counters_t: public endpoint_t {
public:
  counters_t(simif_t* s);
  virtual ~counters_t();
  virtual void init(int argc, char** argv);
  virtual void tick();
  virtual bool done() { return true; } // FIXME: Is it OK?
  void cache(size_t idx);
  void sample(size_t window);
private:
  size_t baudrate;
  size_t sample_idx;
  bool has_cache;
  std::ofstream power_file;
  std::ofstream toggle_file;
  std::string sample_file;
  std::vector<std::string> modules;
  std::vector<std::string> signals;
  std::vector<size_t> widths;
  std::array<size_t, NUM_TOGGLE_COUNTERS> baud_cache;
  std::array<size_t, NUM_TOGGLE_COUNTERS> sample_cache;
  std::vector<double> intercepts;
  std::vector<std::vector<double>> coefs;
  std::vector<std::vector<size_t>> terms;
  std::vector<std::vector<double>> power;
  std::vector<std::vector<double>> samples;

  void read_model(const char* filename);
  inline void compute_power(bool baud, size_t window);
  void dump();
};
#endif // ENABLE_COUNTERS

#endif // __COUNTERS_H
