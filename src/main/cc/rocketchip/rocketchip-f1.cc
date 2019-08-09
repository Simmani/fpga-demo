#include "simif_f1.h"
#include "rocketchip.h"
#include "fesvr/midas_tsi.h"

class rocketchip_f1_t:
  public simif_f1_t,
  public rocketchip_t
{
public:
  rocketchip_f1_t(int argc, char** argv, fesvr_proxy_t* fesvr):
    rocketchip_t(argc, argv, fesvr) { }
};

int main(int argc, char** argv) {
  midas_tsi_t tsi(std::vector<std::string>(argv + 1, argv + argc));
  rocketchip_f1_t rocketchip(argc, argv, &tsi);
  rocketchip.init(argc, argv);
  rocketchip.run(1024 * 1000);
  return rocketchip.finish();
}
