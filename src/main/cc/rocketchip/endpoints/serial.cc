// See LICENSE for license details.

#include "serial.h"

serial_t::serial_t(simif_t* sim, fesvr_proxy_t* fesvr):
  endpoint_t(sim), fesvr(fesvr)
{
}

void serial_t::tick() {
  data.out.ready = true;
  data.out.valid = read(SERIALWIDGET_0(out_valid));
  if (data.out.fire()) {
    data.out.bits = read(SERIALWIDGET_0(out_bits));
    write(SERIALWIDGET_0(out_ready), data.out.ready);
    fesvr->send_word(data.out.bits);
    fesvr->tick();
  }
  if (fesvr->done()) {
    write(SERIALWIDGET_0(exit), true);
  }
}

void serial_t::work() {
  do {
    data.in.valid = fesvr->data_available();
    data.in.ready = data.in.valid ? read(SERIALWIDGET_0(in_ready)) : false;
    if (data.in.fire()) {
      data.in.bits = fesvr->recv_word();
      write(SERIALWIDGET_0(in_bits), data.in.bits);
      write(SERIALWIDGET_0(in_valid), data.in.valid);
    }
    fesvr->tick();
  } while (data.in.fire());
}
