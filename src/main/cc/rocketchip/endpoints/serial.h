// See LICENSE for license details.

#ifndef __SERIAL_H
#define __SERIAL_H

#include "endpoints/endpoint.h"
#include "fesvr/fesvr_proxy.h"

template<class T>
struct serial_data_t {
  struct {
    T bits;
    bool valid;
    bool ready;
    bool fire() { return valid && ready; }
  } in;
  struct {
    T bits;
    bool ready;
    bool valid;
    bool fire() { return valid && ready; }
  } out;
};

class serial_t: public endpoint_t
{
public:
  serial_t(simif_t* sim, fesvr_proxy_t* fesvr);
  void work();
  virtual void init(int argc, char** argv) { }
  virtual void tick();
  virtual bool done() { return fesvr->done() || read(SERIALWIDGET_0(done)); }

private:
  serial_data_t<uint32_t> data;
  fesvr_proxy_t* fesvr;
};

#endif // __SERIAL_H
