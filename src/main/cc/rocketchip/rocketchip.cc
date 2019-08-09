// See LICENSE for license details.

#include "rocketchip.h"
#include "endpoints/uart.h"

rocketchip_t::rocketchip_t(int argc, char** argv, fesvr_proxy_t* fesvr): fesvr(fesvr)
{
  std::vector<std::string> args(argv + 1, argv + argc);
  max_cycles = -1ULL;
  _step_size = -1;
  for (auto &arg: args) {
    if (arg.find("+max-cycles=") == 0) {
      max_cycles = atol(arg.c_str()+12);
    }
    if (arg.find("+step-size=") == 0) {
      _step_size = atol(arg.c_str()+11);
    }
  }

  serial = new serial_t(this, fesvr);
  add_endpoint(serial);
  add_endpoint(new uart_t(this));
}

void rocketchip_t::loadmem() {
  fesvr_loadmem_t loadmem; 
  while (fesvr->recv_loadmem_req(loadmem)) {
    assert(loadmem.size <= 1024);
    static char buf[1024]; // This should be enough...
    fesvr->recv_loadmem_data(buf, loadmem.size);
#ifdef LOADMEM
    const size_t mem_data_bytes = MEM_DATA_CHUNK * sizeof(data_t);
#define WRITE_MEM(addr, src) \
    mpz_t data; \
    mpz_init(data); \
    mpz_import(data, mem_data_bytes / sizeof(uint32_t), -1, sizeof(uint32_t), 0, 0, src); \
    write_mem(addr, data); \
    mpz_clear(data);
#else
    const size_t mem_data_bytes = MEM_DATA_BITS / 8;
#define WRITE_MEM(addr, src) \
    for (auto e: endpoints) { \
      if (sim_mem_t* s = dynamic_cast<sim_mem_t*>(e)) { \
        s->write_mem(addr, src); \
      } \
    }
#endif
    for (size_t off = 0 ; off < loadmem.size ; off += mem_data_bytes) {
      WRITE_MEM(loadmem.addr + off, buf + off);
    }
  }
}

#ifndef ENABLE_SNAPSHOT
#define GET_DELTA step_size
#else
#define GET_DELTA std::min(step_size, get_tracelen())
#endif

void rocketchip_t::loop(size_t step_size) {
  size_t delta = GET_DELTA;
  size_t delta_sum = 0;

  fesvr->tick();
  loadmem();

  do {
    if (fesvr->busy()) {
      step(1);
      delta_sum += 1;
      if (--delta == 0) delta = GET_DELTA;
    } else {
      if (delta_sum + delta == step_size) {
        step(delta - 1);
        fesvr->tick();
        serial->work();
        step(1, false);
      } else {
        step(delta);
      }
      delta_sum += delta;
      delta = GET_DELTA;
    }

    if (delta_sum == step_size || fesvr->busy()) {
      serial->work();
      loadmem(); // FIXME: remove
    }
    if (delta_sum == step_size) delta_sum = 0;
  } while (!fesvr->done() && cycles() <= max_cycles);
}

void rocketchip_t::run(size_t step_size) {
  // Assert reset T=0 -> 5
  target_reset();

  uint64_t start_time = timestamp();
  loop(_step_size > 0 ? _step_size : step_size);
  uint64_t end_time = timestamp();

  double sim_time = diff_secs(end_time, start_time);
  double sim_speed = ((double) cycles()) / (sim_time * 1000.0);
  if (sim_speed > 1000.0) {
    fprintf(stderr, "time elapsed: %.1f s, simulation speed = %.2f MHz\n", sim_time, sim_speed / 1000.0);
  } else {
    fprintf(stderr, "time elapsed: %.1f s, simulation speed = %.2f KHz\n", sim_time, sim_speed);
  }
  int exitcode = fesvr->exit_code();
  if (exitcode) {
    fprintf(stderr, "*** FAILED *** (code = %d) after %llu cycles\n", exitcode,
           (unsigned long long)cycles());
  } else if (cycles() > max_cycles) {
    fprintf(stderr, "*** FAILED *** (timeout) after %llu > %llu cycles\n",
           (unsigned long long)cycles(), (unsigned long long)max_cycles);
    exitcode = -1;
  } else {
    fprintf(stderr, "Completed after %llu cycles\n",
           (unsigned long long)cycles());
  }
  expect(!exitcode, NULL);
}
