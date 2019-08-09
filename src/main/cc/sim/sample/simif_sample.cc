// See LICENSE for license details.

#include "simif.h"
#include <fstream>
#include <iostream>
#include <algorithm>
#include "endpoints/counters.h"

#ifdef ENABLE_SNAPSHOT
#ifdef ENABLE_COUNTERS
extern counters_t* counters;
#endif

void simif_t::init_sampling(int argc, char** argv) {
  // Read mapping files
  sample_t::init_chains(std::string(TARGET_NAME) + ".chain");

  // Init sample variables
  sample_file = std::string(TARGET_NAME) + ".sample";
  sample_num = 30;
  last_snapshot_id = 0;
  snapshot_count = 0;
  snapshot_time = 0;
  sample_cycle = 0;
  profile = false;
  tracelen = TRACE_MAX_LEN;
  trace_count = 0;

  std::vector<std::string> args(argv + 1, argv + argc);
  for (auto &arg: args) {
    if (arg.find("+sample=") == 0) {
      sample_file = arg.c_str() + 8;
    }
    if (arg.find("+samplenum=") == 0) {
      sample_num = strtol(arg.c_str() + 11, NULL, 10);
    }
    if (arg.find("+sample-cycle=") == 0) {
      sample_cycle = strtoll(arg.c_str() + 14, NULL, 10);
    }
    if (arg.find("+tracelen=") == 0) {
      tracelen = strtol(arg.c_str() + 10, NULL, 10);
    }
    if (arg.find("+profile") == 0) {
      profile = true;
    }
  }

  assert(tracelen > 2);
  write(TRACELEN_ADDR, tracelen);

  snapshots = new snapshot_t*[sample_num];
  for (size_t i = 0 ; i < sample_num ; i++) snapshots[i] = NULL;

  // flush output traces by sim reset
  for (size_t k = 0 ; k < OUT_TR_SIZE ; k++) {
    size_t addr = OUT_TR_ADDRS[k];
    size_t chunk = OUT_TR_CHUNKS[k];
    for (size_t off = 0 ; off < chunk ; off++) read(addr+off);
  }
  for (size_t id = 0 ; id < OUT_TR_READY_VALID_SIZE ; id++) {
    read((size_t)OUT_TR_READY_ADDRS[id]);
    read((size_t)OUT_TR_VALID_ADDRS[id]);
    size_t bits_addr = (size_t)OUT_TR_BITS_ADDRS[id];
    size_t bits_chunk = (size_t)OUT_TR_BITS_CHUNKS[id];
    for (size_t off = 0 ; off < bits_chunk ; off++) read(bits_addr + off);
  }
  if (profile) sim_start_time = timestamp();
}

void simif_t::finish_sampling() {
  // tail snapshot
  save_snapshot();
#ifdef ENABLE_COUNTERS
  counters->sample(trace_count);
#endif

  // dump samples
  std::ofstream file(sample_file.c_str(), std::ios_base::out | std::ios_base::trunc);
  sample_t::dump_chains(file);
  for (size_t i = 0 ; i < sample_num ; i++) {
    snapshot_t* snapshot = snapshots[i];
    if (snapshot) {
      sample_t sample(snapshot);
      sample.dump(file);
      delete snapshot;
    }
  }
  delete[] snapshots;
  file.close();

  fprintf(stderr, "Snapshot Count: %llu\n", (unsigned long long)snapshot_count);
  if (profile) {
    double sim_time = diff_secs(timestamp(), sim_start_time);
    fprintf(stderr, "Simulation Time: %.3f s, Snapshot Time: %.3f s\n", 
                    sim_time, diff_secs(snapshot_time, 0));
  }
}

static const size_t data_t_chunks = sizeof(data_t) / sizeof(uint32_t);

void simif_t::read_traces(snapshot_t *snapshot) {
  size_t trace_size = std::min(trace_count, tracelen);
  if (snapshot) snapshot->trace_size = trace_size;
  for (size_t i = 0 ; i < trace_size ; i++) {
    // wire input traces from FPGA
    for (size_t id = 0 ; id < IN_TR_SIZE ; id++) {
      size_t addr = IN_TR_ADDRS[id];
      size_t chunk = IN_TR_CHUNKS[id];
      for (size_t off = 0 ; off < chunk ; off++) {
        data_t data = read(addr+off);
        if (snapshot) snapshot->trace.push_back(data);
      }
    }

    // ready valid input traces from FPGA
    for (size_t id = 0 ; id < IN_TR_READY_VALID_SIZE ; id++) {
      size_t valid_addr = (size_t)IN_TR_VALID_ADDRS[id];
      data_t valid_data = read(valid_addr);
      if (snapshot) snapshot->trace.push_back(valid_data);
      size_t bits_addr = (size_t)IN_TR_BITS_ADDRS[id];
      size_t bits_chunk = (size_t)IN_TR_BITS_CHUNKS[id];
      for (size_t off = 0 ; off < bits_chunk ; off++) {
        data_t data = read(bits_addr + off);
        // We need all input traces
        if (snapshot) snapshot->trace.push_back(data);
      }
    }

    for (size_t id = 0 ; id < OUT_TR_READY_VALID_SIZE ; id++) {
      size_t ready_addr = (size_t)OUT_TR_READY_ADDRS[id];
      data_t ready_data = read(ready_addr);
      if (snapshot) snapshot->trace.push_back(ready_data);
    }

    // wire output traces from FPGA
    for (size_t id = 0 ; id < OUT_TR_SIZE ; id++) {
      size_t addr = OUT_TR_ADDRS[id];
      size_t chunk = OUT_TR_CHUNKS[id];
      for (size_t off = 0 ; off < chunk ; off++) {
        data_t data = read(addr+off);
        if (snapshot && i > 0) snapshot->trace.push_back(data);
      }
    }

    // ready valid output traces from FPGA
    for (size_t id = 0 ; id < OUT_TR_READY_VALID_SIZE ; id++) {
      size_t valid_addr = (size_t)OUT_TR_VALID_ADDRS[id];
      data_t valid_data = read(valid_addr);
      if (snapshot) snapshot->trace.push_back(valid_data);
      size_t bits_addr = (size_t)OUT_TR_BITS_ADDRS[id];
      size_t bits_chunk = (size_t)OUT_TR_BITS_CHUNKS[id];
      for (size_t off = 0 ; off < bits_chunk ; off++) {
        data_t data = read(bits_addr + off);
        // Check only when valid is up
        if (snapshot && valid_data) snapshot->trace.push_back(data);
      }
    }
    for (size_t id = 0 ; id < IN_TR_READY_VALID_SIZE ; id++) {
      size_t ready_addr = (size_t)IN_TR_READY_ADDRS[id];
      data_t ready_data = read(ready_addr);
      if (snapshot) snapshot->trace.push_back(ready_data);
    }
  }
}

void simif_t::read_snapshot(bool load) {
  snapshot_t* snapshot = load ? NULL : snapshots[last_snapshot_id];
  if (snapshot) snapshot->cycle = cycles();
  size_t state_idx = 0;
  for (size_t t = 0 ; t < CHAIN_NUM ; t++) {
    CHAIN_TYPE type = static_cast<CHAIN_TYPE>(t);
    const size_t chain_loop = sample_t::get_chain_loop(type);
    const size_t chain_len  = sample_t::get_chain_len(type);
    for (size_t k = 0 ; k < chain_loop ; k++) {
      if (!load) write(CHAIN_COPY_ADDR[t], 1);
      for (size_t j = 0 ; j < chain_len ; j++) {
        // TODO: write arbitrary values
        if (load) write(CHAIN_IN_ADDR[t], 0);
        data_t value = read(CHAIN_OUT_ADDR[t]);
        if (!load) snapshot->state[state_idx] = value;
        state_idx++;
      }
      if (load) {
        write(CHAIN_LOAD_ADDR[t], 1);
        write(CHAIN_COPY_ADDR[t], 1); // to generate new addrs
      }
    }
  }
  assert(state_idx == snapshot_t::get_state_size());
}

void simif_t::save_snapshot() {
  snapshot_t* snapshot = snapshots[last_snapshot_id];
  if (snapshot) read_traces(snapshot);
}

void simif_t::reservoir_sampling(size_t n) {
  if (t % tracelen == 0) {
    midas_time_t start_time = 0;
    uint64_t record_id = t / tracelen;
    uint64_t snapshot_id = record_id < sample_num ? record_id : gen() % (record_id + 1);
#ifdef ENABLE_COUNTERS
    counters->sample(trace_count);
#endif
    if (snapshot_id < sample_num) {
      if (profile) start_time = timestamp();
      save_snapshot();
      last_snapshot_id = snapshot_id;
      if (!snapshots[last_snapshot_id]) {
        snapshots[last_snapshot_id] = new snapshot_t;
      } else {
        snapshots[last_snapshot_id]->trace.clear();
      }
      read_snapshot();
      snapshot_count++;
      trace_count = 0;
#ifdef ENABLE_COUNTERS
      counters->cache(snapshot_id);
#endif
      if (profile) snapshot_time += (timestamp() - start_time);
    }
  }
  if (trace_count < tracelen) trace_count += n;
}

void simif_t::deterministic_sampling(size_t n) {
  if ((t + n) > sample_cycle && (t + n) - sample_cycle <= tracelen) {
    fprintf(stderr, "Snapshot at %llu\n", (unsigned long long)cycles());
    // flush trace buffer
    trace_count = std::min((size_t)(t + n), tracelen);
    read_traces(NULL);
    save_snapshot();
    trace_count = 0;
    // take a snaphsot
    last_snapshot_id = 0;
    snapshots[0] = new snapshot_t;
    read_snapshot();
  }
  if ((t + n) > sample_cycle && trace_count < tracelen) {
    trace_count += n;
  }
}
#endif
