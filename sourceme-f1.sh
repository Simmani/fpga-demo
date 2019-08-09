unamestr=$(uname)
RDIR=$(pwd)

AWSFPGA=$RDIR/platforms/f1
cd $AWSFPGA
source hdk_setup.sh
source sdk_setup.sh
cd $RDIR
