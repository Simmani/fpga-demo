// See LICENSE for license details.

#ifndef __ROCKETCHIP_H
#define __ROCKETCHIP_H

#include "simif.h"
#include "fesvr/fesvr_proxy.h"
#include "endpoints/serial.h"

class rocketchip_t: virtual simif_t
{
public:
  rocketchip_t(int argc, char** argv, fesvr_proxy_t* fesvr);
  ~rocketchip_t() { }

  void run(size_t step_size);
  void loadmem();

private:
  fesvr_proxy_t* fesvr;
  serial_t* serial;
  uint64_t max_cycles;
  ssize_t _step_size;
  void loop(size_t step_size);
};

#endif // __ROCKETCHIP_H
