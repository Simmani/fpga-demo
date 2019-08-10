# <a name="step1"></a> Step 1: Compiling the FPGA-based Simulator

This step explains how to compile the FPGA-based simulator and generate its AFI. This may take 5~6 hours, and thus, you may skip to [Step 2](#step2) to run the FPGA-based simulator with the pre-generated AFI.

## Step 1-1: Getting Started

* Launch an `c4.x8large` or `c5.x9large` instance with [FPGA Developer AMI v1.5.0](https://aws.amazon.com/marketplace/pp/B06VVYBLZZ) (ami-0da0d9ed98b33a214). We recommend US East(N. Virginia) for the region and 300GB for the root storage.
* Configure the instance
```
$ aws configure
AWS Access Key ID [None]: <your key>
AWS Secret Access Key [None]: <your secret key>
Default region name [None]: us-east-1
Default output format [None]: json
```
* Initialize this repo
```
git clone https://github.com/Simmani/fpga-demo.git
cd fpga-demo
./setup.sh
```

## Step 1-2: Generating Verilog
```
scons fpga-v
```

## Step 1-3: (Optional) Run tests
```
scons verilator       # Warning: this may take tens of minutes
scons run-asm-tests   # Run assembly tests
scons run-bmark-tests # Run microbenchmarks
```

## Step 1-4: Generating Bitstream
```
export EMAIL=<your email address> # To recieve notification
source sourceme-f1.sh
scons fpga
```
This may take 4~5 hours. You'll be notified by email when this is done.

## Step 1-5: Creating the AFI
```
scons bucket=<your S3 bucket name> [name=<AFI name>]
...
{
    "FpgaImageId": "afi-010e71c45e10b9afb",
    "FpgaImageGlobalId": "agfi-033d1f2bff292a5b7"
}
```
This may take ~1 hour. It's important keep both `FpgaImageId` and `FpgaImageGlobalId`. To check the status, run:
```
aws ec2 describe-fpga-images --fpga-image-ids <FpgaImageId>
```
You're ready for [Step 2](step2) when `State` becomes `available`.

# <a name="step2"></a> Step 2: Running SqueezeNet on Hwacha with the FPGA-based simulator
