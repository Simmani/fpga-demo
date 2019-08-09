// See LICENSE for license details.

#include "sample.h"
#include <cassert>
#include <cstring>
#include <fstream>
#include <sstream>

#ifdef ENABLE_SNAPSHOT
size_t snapshot_t::state_size = 0;

std::array<std::vector<std::string>, CHAIN_NUM> sample_t::signals = {};
std::array<std::vector<size_t>,      CHAIN_NUM> sample_t::widths  = {};
std::array<std::vector<int>,         CHAIN_NUM> sample_t::depths = {};
size_t sample_t::chain_len[CHAIN_NUM]  = {0};
size_t sample_t::chain_loop[CHAIN_NUM] = {0};

void sample_t::init_chains(std::string filename) {
  std::fill(signals.begin(), signals.end(), std::vector<std::string>());
  std::fill(widths.begin(),  widths.end(),  std::vector<size_t>());
  std::fill(depths.begin(),  depths.end(),  std::vector<int>());
  std::ifstream file(filename.c_str());
  if (!file) {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(EXIT_FAILURE);
  }
  std::string line;
  while (std::getline(file, line)) {
    std::istringstream iss(line);
    size_t type;
    std::string signal;
    iss >> type >> signal;
    size_t width;
    int depth;
    iss >> width >> depth;
    if (signal == "null") signal = "";
    signals[type].push_back(signal);
    widths[type].push_back(width);
    depths[type].push_back(depth);
    chain_len[type] += width;
    switch ((CHAIN_TYPE) type) {
      case REGS_CHAIN:
      case TRACE_CHAIN:
        chain_loop[type] = 1;
        break;
      case MEMS_CHAIN:
      case REGFILE_CHAIN:
      case SRAM_CHAIN:
        if (!signal.empty() && depth > 0) {
          chain_loop[type] = std::max(chain_loop[type], (size_t) depth);
        }
        break;
      default:
        assert(false);
        break;
    }
  }
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    assert(chain_len[t] % DAISY_WIDTH == 0);
    chain_len[t] /= DAISY_WIDTH;
    snapshot_t::state_size += (chain_loop[t] * chain_len[t]);
  }

  file.close();
}

void sample_t::dump_chains(std::ostream& os) {
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    auto chain_signals = signals[t];
    auto chain_widths = widths[t];
    for (size_t id = 0 ; id < chain_signals.size() ; id++) {
      auto signal = chain_signals[id];
      auto width = chain_widths[id];
      os << SIGNALS << " " << t << " " <<
        (signal.empty() ? "null" : signal) << " " << width << std::endl;
    }
  }
  for (size_t id = 0 ; id < IN_TR_SIZE ; id++) {
    os << SIGNALS << " " << IN_TR << " " << IN_TR_NAMES[id] << std::endl;
  }
  for (size_t id = 0 ; id < OUT_TR_SIZE ; id++) {
    os << SIGNALS << " " << OUT_TR << " " << OUT_TR_NAMES[id] << std::endl;
  }
  for (size_t id = 0, bits_id = 0 ; id < IN_TR_READY_VALID_SIZE ; id++) {
    os << SIGNALS << " " << IN_TR_VALID << " " <<
      (const char*)IN_TR_READY_VALID_NAMES[id] << "_valid" << std::endl;
    os << SIGNALS << " " << IN_TR_READY << " " <<
      (const char*)IN_TR_READY_VALID_NAMES[id] << "_ready" << std::endl;
    for (size_t k = 0 ; k < (size_t)IN_TR_BITS_FIELD_NUMS[id] ; k++, bits_id++) {
      os << SIGNALS << " " << IN_TR_BITS << " " <<
        (const char*)IN_TR_BITS_FIELD_NAMES[bits_id] << std::endl;
    }
  }
  for (size_t id = 0, bits_id = 0 ; id < OUT_TR_READY_VALID_SIZE ; id++) {
    os << SIGNALS << " " << OUT_TR_VALID << " " <<
      (const char*)OUT_TR_READY_VALID_NAMES[id] << "_valid" << std::endl;
    os << SIGNALS << " " << OUT_TR_READY << " " <<
      (const char*)OUT_TR_READY_VALID_NAMES[id] << "_ready" << std::endl;
    for (size_t k = 0 ; k < (size_t)OUT_TR_BITS_FIELD_NUMS[id] ; k++, bits_id++) {
      os << SIGNALS << " " << OUT_TR_BITS << " " <<
        (const char*)OUT_TR_BITS_FIELD_NAMES[bits_id] << std::endl;
    }
  }
}

static inline char* int_to_bin(char *bin, data_t value, size_t size) {
  for (size_t i = 0 ; i < size; i++) {
    bin[i] = ((value >> (size-1-i)) & 0x1) + '0';
  }
  bin[size] = 0;
  return bin;
}


size_t sample_t::read_chain(CHAIN_TYPE type, const char* snap, size_t start) {
  size_t t = static_cast<size_t>(type);
  auto chain_signals = signals[t];
  auto chain_widths = widths[t];
  auto chain_depths = depths[t];
  for (size_t i = 0 ; i < chain_loop[type] ; i++) {
    for (size_t s = 0 ; s < chain_signals.size() ; s++) {
      auto signal = chain_signals[s];
      auto width = chain_widths[s];
      auto depth = chain_depths[s];
      if (!signal.empty()) {
        char substr[1025];
        assert(width <= 1024);
        strncpy(substr, snap+start, width);
        substr[width] = '\0';
        mpz_t* value = (mpz_t*)malloc(sizeof(mpz_t));
        mpz_init(*value);
        mpz_set_str(*value, substr, 2);
        switch(type) {
          case TRACE_CHAIN:
            add_cmd(new force_t(type, s, value));
            break;
          case REGS_CHAIN:
            add_cmd(new load_t(type, s, value));
            break;
          case MEMS_CHAIN:
          case REGFILE_CHAIN:
          case SRAM_CHAIN:
            if (static_cast<int>(i) < depth)
              add_cmd(new load_t(type, s, value, i));
            break;
          default:
            assert(false);
            break;
        }
      }
      start += width;
    }
    assert(start % DAISY_WIDTH == 0);
  }
  return start;
}

void sample_t::read_state(data_t* state) {
  size_t state_idx = 0;
  std::ostringstream snap;
  char bin[DAISY_WIDTH+1]; 
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    CHAIN_TYPE type = static_cast<CHAIN_TYPE>(t);
    const size_t chain_loop = sample_t::get_chain_loop(type);
    const size_t chain_len  = sample_t::get_chain_len(type);
    for (size_t k = 0 ; k < chain_loop ; k++) {
      for (size_t j = 0 ; j < chain_len ; j++) {
        snap << int_to_bin(bin, state[state_idx++], DAISY_WIDTH);
      }
    }
  }
  assert(state_idx == snapshot_t::get_state_size());

  size_t start = 0;
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    CHAIN_TYPE type = static_cast<CHAIN_TYPE>(t);
    start = read_chain(type, snap.str().c_str(), start);
  }
}

size_t sample_t::read_trace_ready_valid_bits(
    std::deque<data_t>& trace,
    bool poke,
    size_t id,
    size_t bits_id) {
  size_t bits_addr = poke ? (size_t)IN_TR_BITS_ADDRS[id] : (size_t)OUT_TR_BITS_ADDRS[id];
  size_t bits_chunk = poke ? (size_t)IN_TR_BITS_CHUNKS[id] : (size_t)OUT_TR_BITS_CHUNKS[id];
  size_t num_fields = poke ? (size_t)IN_TR_BITS_FIELD_NUMS[id] : (size_t)OUT_TR_BITS_FIELD_NUMS[id];
  data_t *bits_data = new data_t[bits_chunk];
  for (size_t off = 0 ; off < bits_chunk ; off++) {
    bits_data[off] = trace.front();
    trace.pop_front();
  }
  mpz_t data;
  mpz_init(data);
  mpz_import(data, bits_chunk, -1, sizeof(data_t), 0, 0, bits_data);
  for (size_t k = 0, off = 0 ; k < num_fields ; k++, bits_id++) {
    size_t field_width = ((unsigned int*)(
      poke ? IN_TR_BITS_FIELD_WIDTHS : OUT_TR_BITS_FIELD_WIDTHS))[bits_id];
    mpz_t *value = (mpz_t*)malloc(sizeof(mpz_t)), mask;
    mpz_inits(*value, mask, NULL);
    // value = data >> off
    mpz_fdiv_q_2exp(*value, data, off);
    // mask = (1 << field_width) - 1
    mpz_set_ui(mask, 1);
    mpz_mul_2exp(mask, mask, field_width);
    mpz_sub_ui(mask, mask, 1);
    // *value = *value & mask
    mpz_and(*value, *value, mask);
    mpz_clear(mask);
    add_cmd(poke ?
      (sample_inst_t*) new poke_t(IN_TR_BITS, bits_id, value):
      (sample_inst_t*) new expect_t(OUT_TR_BITS, bits_id, value));
    off += field_width;
  }
  mpz_clear(data);
  delete[] bits_data;
  return bits_id;
}

void sample_t::read_trace(std::deque<data_t>& trace, size_t size) {
  for (size_t i = 0 ; i < size ; i++) {
    for (size_t id = 0 ; id < IN_TR_SIZE ; id++) {
      size_t addr = IN_TR_ADDRS[id];
      size_t chunk = IN_TR_CHUNKS[id];
      data_t *data = new data_t[chunk];
      for (size_t off = 0 ; off < chunk ; off++) {
        data[off] = trace.front();
        trace.pop_front();
      }
      mpz_t *value = (mpz_t*)malloc(sizeof(mpz_t));
      mpz_init(*value);
      mpz_import(*value, chunk, -1, sizeof(data_t), 0, 0, data);
      add_cmd(new poke_t(IN_TR, id, value));
    }
    for (size_t id = 0, bits_id = 0 ; id < IN_TR_READY_VALID_SIZE ; id++) {
      size_t valid_addr = (size_t)IN_TR_VALID_ADDRS[id];
      data_t valid_data = trace.front();
      trace.pop_front();
      mpz_t* value = (mpz_t*)malloc(sizeof(mpz_t));
      mpz_init(*value);
      mpz_set_ui(*value, valid_data);
      add_cmd(new poke_t(IN_TR_VALID, id, value));
      bits_id = read_trace_ready_valid_bits(trace, true, id, bits_id);
    }
    for (size_t id = 0 ; id < OUT_TR_READY_VALID_SIZE ; id++) {
      size_t ready_addr = (size_t)OUT_TR_READY_ADDRS[id];
      data_t ready_data = trace.front();
      trace.pop_front();
      mpz_t* value = (mpz_t*)malloc(sizeof(mpz_t));
      mpz_init(*value);
      mpz_set_ui(*value, ready_data);
      add_cmd(new poke_t(OUT_TR_READY, id, value));
    }

    add_cmd(new step_t(1));

    // wire output traces from FPGA
    for (size_t id = 0 ; id < OUT_TR_SIZE ; id++) {
      if (i == 0) break;
      size_t addr = OUT_TR_ADDRS[id];
      size_t chunk = OUT_TR_CHUNKS[id];
      data_t *data = new data_t[chunk];
      for (size_t off = 0 ; off < chunk ; off++) {
        data[off] = trace.front();
        trace.pop_front();
      }
      mpz_t *value = (mpz_t*)malloc(sizeof(mpz_t));
      mpz_init(*value);
      mpz_import(*value, chunk, -1, sizeof(data_t), 0, 0, data);
      add_cmd(new expect_t(OUT_TR, id, value));
      delete[] data;
    }

    // ready valid output traces from FPGA
    for (size_t id = 0, bits_id = 0 ; id < OUT_TR_READY_VALID_SIZE ; id++) {
      size_t valid_addr = (size_t)OUT_TR_VALID_ADDRS[id];
      data_t valid_data = trace.front();
      trace.pop_front();
      mpz_t* value = (mpz_t*)malloc(sizeof(mpz_t));
      mpz_init(*value);
      mpz_set_ui(*value, valid_data);
      add_cmd(new expect_t(OUT_TR_VALID, id, value));
      bits_id = !valid_data ?
        bits_id + (size_t)OUT_TR_BITS_FIELD_NUMS[id] :
        read_trace_ready_valid_bits(trace, false, id, bits_id);
    }
    for (size_t id = 0 ; id < IN_TR_READY_VALID_SIZE ; id++) {
      size_t ready_addr = (size_t)IN_TR_READY_ADDRS[id];
      data_t ready_data = trace.front();
      trace.pop_front();
      mpz_t* value = (mpz_t*)malloc(sizeof(mpz_t));
      mpz_init(*value);
      mpz_set_ui(*value, ready_data);
      add_cmd(new expect_t(IN_TR_READY, id, value));
    }
  }
  // add_cmd(new step_t(5)); // to catch assertions in replay
}

sample_t::sample_t(snapshot_t* snapshot):
    cycle(snapshot->cycle), force_prev_id(-1) {
  read_state(snapshot->state);
  read_trace(snapshot->trace, snapshot->trace_size);
}

sample_t::sample_t(const char* snap, uint64_t cycle):
    cycle(cycle), force_prev_id(-1) {
  size_t start = 0;
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    CHAIN_TYPE type = static_cast<CHAIN_TYPE>(t);
    start = read_chain(type, snap, start);
  }
}

sample_t::sample_t(CHAIN_TYPE type, const char* snap, uint64_t cycle):
    cycle(cycle), force_prev_id(-1) {
  read_chain(type, snap);
}
#endif

sample_t::~sample_t() {
  for (auto& cmd: cmds) delete cmd;
  cmds.clear();
}
