#!/usr/bin/env bash
git config --global submodule.riscv-tools.update none
git config --global submodule.torture.update none
git config --global submodule.SDAccel/examples/xilinx_2017.4.update none
git config --global submodule.SDAccel/examples/xilinx_2018.2.update none
git submodule update --init --recursive
git config --global --unset submodule.riscv-tools.update
git config --global --unset submodule.torture.update
git config --global --unset submodule.SDAccel/examples/xilinx_2017.4.update
git config --global --unset submodule.SDAccel/examples/xilinx_2018.2.update

sudo yum groupinstall -y "Development tools"
sudo yum install -y gmp-devel mpfr-devel libmpc-devel zlib-devel
sudo yum install -y java java-devel
sudo yum install -y vim python2-pip python2-devel
sudo yum install -y https://centos7.iuscommunity.org/ius-release.rpm
sudo yum install -y python36u python36u-pip python36u-devel
sudo pip install --upgrade pip
sudo pip install scons
sudo pip install numpy
sudo pip install matplotlib
curl https://bintray.com/sbt/rpm/rpm | sudo tee /etc/yum.repos.d/bintray-sbt-rpm.repo
sudo yum install -y sbt

DTCversion=dtc-1.4.4
wget https://git.kernel.org/pub/scm/utils/dtc/dtc.git/snapshot/$DTCversion.tar.gz
tar -xvf $DTCversion.tar.gz
cd $DTCversion
make -j16
make install
cd ..
rm -rf $DTCversion.tar.gz
rm -rf $DTCversion

mkdir -p ~/.vim/{ftdetect,indent,syntax} && for d in ftdetect indent syntax ; do wget -O ~/.vim/$d/scala.vim https://raw.githubusercontent.com/derekwyatt/vim-scala/master/$d/scala.vim; done


# install verilator
git clone http://git.veripool.org/git/verilator
cd verilator/
git checkout v4.002
autoconf && ./configure && make -j16 && sudo make install
cd ..

echo 'Installing RISC-V tools with prebuilt images'
git clone https://github.com/Simmani/esp-tools-prebuilt.git
cd esp-tools-prebuilt
cat riscv.tar.part* > riscv.tar
tar -xvf riscv.tar
mv riscv ../../
rm riscv.tar
cd ..
