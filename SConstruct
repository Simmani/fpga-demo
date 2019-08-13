import os
import datetime
from operator import add
from functools import reduce

ROCKETCHIP_TEST_SUITES = ['asm', 'bmark']

def compile_verilog(env):
    # Compile FIRRTL first
    def publish(target, source, env):
        with open(target[0].name, 'w') as _f:
            _f.write(str(datetime.datetime.now()))

    env.SBT('publish', [],
            SBT_CWD='firrtl',
            SBT_CMD='publishLocal',
            SBT_ACTIONS=[publish])
    env.Precious('publish')
    env.NoClean('publish')

    targets = env.SBT(
        [
            os.path.join(env['GEN_DIR'], 'FPGATop.v'),
            os.path.join(env['GEN_DIR'], env['DESIGN'] + '-const.h'),
            os.path.join(env['GEN_DIR'], env['DESIGN'] + '-const.vh'),
            os.path.join(env['GEN_DIR'], 'defines.vh')
        ],
        ['publish'] + [
            os.path.abspath('macro.45nm.json'),
            os.path.abspath('model.tsmc45.csv'),
            os.path.abspath('cad-opt.txt')
        ],
        SBT_CMD='"%s"' % ' '.join([
            "runMain",
            'dessert.rocketchip.Generator',
            'midas',
            env['PLATFORM'],
            env['GEN_DIR'],
            'dessert.rocketchip',
            'HwachaTop',
            'dessert.rocketchip',
            'ExampleHwachaConfig',
            '+svf=%s' % os.path.abspath("cad-opt.txt"),
            '+macro=%s' % os.path.abspath("macro.45nm.json"),
            '+model=%s' % os.path.abspath("model.tsmc45.csv")]))
    env.Precious(targets)
    env.Alias('fpga-v', targets)
    env.SideEffect('#sbt', targets)
    env.Default('fpga-v')

    return [t.abspath for t in targets]

def run_emul(env):
    out_dir = env['OUT_DIR']
    makefrag = os.path.join(env['GEN_DIR'], 'dessert.rocketchip.d')
    env.Depends(makefrag, 'fpga-v')
    name = '%s%s' % (
        'V' if env['EMUL'] == 'verilator' else '', env['DESIGN'])
    emul = os.path.join(out_dir, name)
    sim_args = env['SIM_ARGS'] + [
        '+baudrate=%d' % 128,
        '+max-cycles=%d' % 100000000,
    ]
    targets = reduce(add, [
        [
            '#run-%s-tests' % test_suite,
            '#run-%s-tests-fast' % test_suite,
            '#run-%s-tests-debug' % test_suite,
        ]
        for test_suite in ROCKETCHIP_TEST_SUITES
    ])
    for target in targets:
        suffix = '-debug' if target.endswith('-debug') else ''
        env.Alias(target[1:], env.RocketChipTest(
            target,
            [emul + suffix, makefrag],
            SIM_ARGS=sim_args))
    env.SideEffect('#make', targets)

def run_sim(env):
    Import('driver')
    if 'run' in ARGUMENTS:
        run = [
            os.path.abspath(x)
            if os.path.isfile(os.path.abspath(x))
            else x for x in ARGUMENTS['run'].split(' ')
        ]
        benchmark = '-'.join([os.path.basename(x) for x in run])
        agfi = ARGUMENTS.get('agfi', 'agfi-033d1f2bff292a5b7')
        env.Default(env.Alias('run', env.Command('#run', driver, [
            ' '.join(['sudo', 'fpga-clear-local-image', '-S', '0']),
            ' '.join(['sudo', 'fpga-load-local-image', '-S', '0', '-I', agfi]),
            ' '.join([
                'cd', '$SOURCE.dir', '&&',
                'sudo', './$SOURCE.file'
            ] + run + [
                #'+sample=%s.sample' % benchmark,
                '+power=%s-power.csv' % benchmark,
                #'+toggle=%s-toggle.csv' % benchmark,
                #'+sample-pwr=%s-sample.csv' % benchmark,
                '+baudrate=100000',
                "+model=%s" % os.path.abspath("model.tsmc45.csv")
            ] + env['SIM_ARGS'] + [
                '|&', 'tee', '%s.out' % benchmark
            ]),
            ' '.join([
                os.path.abspath('plot-power.py'),
                '-d', os.path.join(env['OUT_DIR'], benchmark),
                '-p', os.path.join(env['OUT_DIR'], benchmark + '-power.csv')
            ])
        ])))

def _get_submodule_files(submodule):
    return reduce(add, [
        [
            os.path.join(dirpath, f)
            for f in filenames if f.endswith('.scala')
        ]
        for dirpath, _, filenames in os.walk(os.path.join(
            submodule, 'src', 'main', 'scala'))
    ], [])

def _scala_srcs(target, source, env):
    if target[0].name == 'publish':
        return target, source + _get_submodule_files('firrtl')

    extra_srcs = ['publish']

    submodules = [
        os.path.curdir,
        os.path.join('designs', 'rocket-chip'),
        os.path.join('designs', 'testchipip'),
        os.path.join('designs', 'sifive-blocks'),
        os.path.join('designs', 'hwacha')
    ]

    return target, source + ['publish'] + reduce(add, [
        _get_submodule_files(submodule) for submodule in submodules
    ], [])

def _sbt_actions(target, source, env, for_signature):
    return [' '.join(
        (['cd', env['SBT_CWD'], '&&'] if 'SBT_CWD' in env else []) + \
        [env['SBT'], env['SBT_FLAGS'], env['SBT_CMD']]
    )] + (env['SBT_ACTIONS'] if 'SBT_ACTIONS' in env else [])

def _rocketchip_test_actions(target, source, env, for_signature):
    output_dir = env['TEST_OUT_DIR'] if 'TEST_OUT_DIR' in env else source[0].dir.abspath
    return ([
        Mkdir(output_dir)
    ] if not os.path.isdir(output_dir) else []) + [
        ' '.join([
            'make',
            target[0].name \
            if target[0].name.startswith('run-') \
            else target[0].abspath,
            '-C', os.path.join('src', 'main', 'makefile'),
            '-f', 'rocketchip.mk',
            '-j',  str(GetOption('num_jobs')),
            'EMUL=' + source[0].abspath,
            'makefrag=' + source[1].abspath,
            'output_dir=' + output_dir,
            'SIM_ARGS="%s"' % ' '.join(env['SIM_ARGS']),
            'DISASM_EXTENSION="%s"' % '--extension=hwacha'
        ])
    ]

variables = Variables(None, ARGUMENTS)
variables.AddVariables(
    EnumVariable('PLATFORM', 'Host platform', 'f1', allowed_values=['zynq', 'f1']),
    EnumVariable('EMUL', 'Program for emulation, RTL/gate-level simulation',
                 'verilator', allowed_values=['vcs', 'verilator']))

env = Environment(
    variables=variables,
    ENV=os.environ,
    SBT='sbt',
    SBT_FLAGS=' '.join([
        '-ivy', os.path.join(os.path.abspath(os.path.curdir), '.ivy2'),
        '-J-Xmx16G',
        '-J-Xss8M',
        '-J-XX:MaxMetaspaceSize=512M',
        '++2.12.4'
    ]),
    VERILATOR='verilator --cc --exe',
    VCS='vcs -full64',
    PLATFORM='f1',
    RISCV=os.environ.get('RISCV', '/home/centos/riscv'),
    DESIGN='HwachaTop',
    GEN_DIR=os.path.abspath('generated-src'),
    OUT_DIR=os.path.abspath('output'),
    SIM_ARGS=[
        "+mm_MEM_LATENCY=80",
        "+mm_LLC_LATENCY=1",
        "+mm_LLC_WAY_BITS=2",
        "+mm_LLC_SET_BITS=12",
        "+mm_LLC_BLOCK_BITS=6",
        "+model=%s" % os.path.abspath("model.tsmc45.csv")
    ],
    CLOCK_PERIOD=1.0
)

env.Append(
    BUILDERS={
        'SBT': Builder(generator=_sbt_actions, emitter=_scala_srcs),
        'RocketChipTest': Builder(generator=_rocketchip_test_actions)
    },
)

num_cpus = 0
with open('/proc/cpuinfo', 'r') as _f:
    for line in _f:
        if line[:9] == 'processor':
            num_cpus += 1
print("# of processors: %d" % num_cpus)

if GetOption('num_jobs') < 8:
    SetOption('num_jobs', max(num_cpus-4, 8))
print("# of job: %d" % GetOption('num_jobs'))

verilog, const_h, const_vh, defines = compile_verilog(env)

fpga_dir = os.path.abspath(os.path.join('platforms', 'f1'))
env.SConscript(
    os.path.join('src', 'main', 'cc', 'SConscript'),
    exports=[
        'env', 'fpga_dir', 'verilog', 'const_h', 'const_vh'
    ])
env.SConscript(
    os.path.join('platforms', 'SConscript'),
    exports=['env', 'verilog', 'defines'])

run_emul(env)
run_sim(env)
