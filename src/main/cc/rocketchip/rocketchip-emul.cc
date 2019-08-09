//See LICENSE for license details.

#include "simif_emul.h"
#include "rocketchip.h"
#include "fesvr/midas_tsi.h"

class rocketchip_emul_t:
  public simif_emul_t,
  public rocketchip_t
{
public:
  rocketchip_emul_t(int argc, char** argv, fesvr_proxy_t* fesvr):
    rocketchip_t(argc, argv, fesvr) { }
};

int main(int argc, char** argv) {
  midas_tsi_t tsi(std::vector<std::string>(argv + 1, argv + argc));
  rocketchip_emul_t rocketchip(argc, argv, &tsi);
  rocketchip.init(argc, argv);
  rocketchip.run(128);
  return rocketchip.finish();
}
