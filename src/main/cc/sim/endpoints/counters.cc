#include "counters.h"
#include <algorithm>
#include <iostream>
#include <iomanip>
#include <cstring>

#ifdef ENABLE_COUNTERS

#define get_token(x) token = strtok((x), ",\r")
#define init_token get_token((char*)line.c_str())
#define next_token get_token(NULL)

counters_t::counters_t(simif_t* s):
  endpoint_t(s), has_cache(false)
{
  std::fill(baud_cache.begin(), baud_cache.end(), 0);
  std::fill(sample_cache.begin(), sample_cache.end(), 0);
}

counters_t::~counters_t()
{
  dump();
  power_file.close();
  if (toggle_file.is_open()) toggle_file.close();
}

void counters_t::init(int argc, char** argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  std::string model_file = "model.csv";
  sample_file = "samples.csv";
  baudrate = 128;
  for (auto &arg: args) {
    if (arg.find("+model=") == 0) {
      model_file = arg.c_str() + 7;
    }
    if (arg.find("+power=") == 0) {
      power_file.open(arg.c_str() + 7);
    }
    if (arg.find("+sample-pwr=") == 0) {
      sample_file = arg.c_str() + 12;
    }
    if (arg.find("+toggle=") == 0) {
      toggle_file.open(arg.c_str() + 8);
    }
    if (arg.find("+baudrate=") == 0) {
      baudrate = strtol(arg.c_str() + 10, NULL, 10);
    }
  }
  assert(power_file.is_open());
  read_model(model_file.c_str());
  write(COUNTER_BAUD_RATE, baudrate);
}

void counters_t::tick() {
  if (read(COUNTER_BAUD))
    compute_power(true, baudrate);
}

void counters_t::cache(size_t idx) {
  write(COUNTER_READ, true);
  for (size_t i = 0 ; i < NUM_TOGGLE_COUNTERS ; i++) {
    sample_cache[i] = read(TOGGLE_COUNTERS[i]);
  }
  has_cache = true;
  sample_idx = idx;
}

void counters_t::sample(size_t window) {
  if (has_cache) {
    write(COUNTER_READ, true);
    compute_power(false, window);
  }
  has_cache = false;
}

void counters_t::read_model(const char* filename) {
  std::ifstream file(filename);
  if (!file) {
    fprintf(stderr, "Cannot open %s\n", filename);
    exit(EXIT_FAILURE);
  }
  size_t i = 0;
  ssize_t counter_id = -1;
  std::string line;
  char* token;
  while (std::getline(file, line)) {
    switch(i) {
      case 0:
        // signals
        init_token;
        assert(strcmp(token, "signals") == 0);
        while ((next_token)) {
          signals.push_back(std::string(token));
        }
        assert(signals.size() == NUM_TOGGLE_COUNTERS);
        break;
      case 1:
        // widths
        init_token;
        assert(strcmp(token, "widths") == 0);
        while ((next_token)) {
          widths.push_back(atoi(token));
        }
        assert(widths.size() == NUM_TOGGLE_COUNTERS);
        break;
      case 2:
        // modules
        init_token;
        assert(strcmp(token, "modules") == 0);
        while ((next_token)) {
          modules.push_back(std::string(token));
        }
        break;
      case 3:
        // const
        init_token;
        assert(strcmp(token, "const") == 0);
        while ((next_token)) {
          intercepts.push_back(atof(token));
        }
        assert(intercepts.size() == modules.size());
        break;
      default:
        // terms
        init_token;
        char* vars = token;
        std::vector<double> coef;
        while ((next_token)) {
          coef.push_back(atof(token));
        }
        assert(coef.size() == modules.size());
        coefs.push_back(coef);

        std::vector<size_t> term;
        char* var = strtok(vars, "*");
        do {
          auto it = std::find(
            signals.begin(),
            signals.end(),
            std::string(var));
          size_t idx = std::distance(signals.begin(), it);
          term.push_back(idx);
        } while ((var = strtok(NULL, "*")));
        terms.push_back(term);
        break;
    }
    i++;
  }

  power_file << "window" << "," << baudrate << std::endl; 
  auto module_it = modules.begin();
  power_file << *module_it++;
  while (module_it != modules.end()) {
    power_file << "," << *module_it++;
  }
  power_file << std::endl;

  if (toggle_file.is_open()) {
    auto signal_it = signals.begin();
    toggle_file << *signal_it++;
    while (signal_it != signals.end()) {
      toggle_file << "," << *signal_it++;
    }
    toggle_file << std::endl;
    toggle_file << std::fixed << std::setprecision(6);
  }
}

void counters_t::compute_power(bool baud, size_t window) {
  static const size_t module_size = modules.size();
  std::vector<double> p;
  for (size_t k = 0 ; k < module_size ; k++) {
    p.push_back(intercepts[k]);
  }

  uint32_t toggles[NUM_TOGGLE_COUNTERS];
  double toggle_rates[NUM_TOGGLE_COUNTERS];
  for (size_t i = 0 ; i < NUM_TOGGLE_COUNTERS ; i++) {
    uint32_t cur  = read(TOGGLE_COUNTERS[i]);
    uint32_t prev = baud ? baud_cache[i] : sample_cache[i];
    uint32_t toggle_count = cur - prev;
    toggles[i] = cur - prev;
    assert(toggles[i] >= 0);
    if (baud) baud_cache[i] = cur;
    toggle_rates[i] = (double)toggle_count / (widths[i] * window);
  }

  size_t i = 0;
  for (auto& term: terms) {
    double value = 1.0;
    for (auto& var: term) {
      value *= toggle_rates[var];
    }
    for (size_t k = 0 ; k < module_size ; k++) {
      p[k] += coefs[i][k] * value;
    }
    i++;
  }

  if (baud) {
    auto it = p.begin();
    power_file << *it++;
    while (it != p.end()) {
      power_file << "," << *it++;
    }
    power_file << std::endl;

    if (toggle_file.is_open()) {
      toggle_file << toggles[0];
      for (size_t i = 1 ; i < NUM_TOGGLE_COUNTERS ; i++) {
        toggle_file << "," << toggles[i];
      }
      toggle_file << std::endl;
    }
  } else {
    if (samples.size() < (sample_idx + 1))
      samples.resize(sample_idx + 1);
    samples[sample_idx] = p;
  }
}

void counters_t::dump() {
  std::ofstream f(sample_file.c_str());
  auto module_it = modules.begin();
  f << *module_it++;
  while (module_it != modules.end()) {
    f << "," << *module_it++;
  }
  f << std::endl;

  for (auto& p: samples) {
    auto it = p.begin();
    f << *it++;
    while (it != p.end()) {
      f << "," << *it++;
    }
    f << std::endl;
  }
  f.close();
}

#undef get_token
#undef init_token
#undef next_token

#endif // ENABLE_COUNTERS
