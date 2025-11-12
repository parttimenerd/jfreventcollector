#!/usr/bin/python3

import shutil
import os
import tempfile
from pathlib import Path
import sys

os.chdir(Path(__file__).parent.parent)

JAVA_VERSION = "21" # Java version to use for building and running
SITE_FOLDER = "site"

def get_java_version() -> str:
    return subprocess.check_output(
        f"java -version 2>&1 | head -n 1 | cut -d '\"' -f 2",
        shell=True).decode("utf-8").strip()

def clean_gen():
    shutil.rmtree("target", ignore_errors=True)
    shutil.rmtree(SITE_FOLDER, ignore_errors=True)


def build_generator():
    if os.path.exists("target"):
        return
    os.system("mvn dependency:resolve -U")
    os.system("mvn clean package assembly:single")


def build_site():
    build_generator()
    print("Building site...")
    os.system(f"java -jar target/jfrevents-site-generator.jar generate {SITE_FOLDER}")



def cli():
    commands = {
        "clean_gen": clean_gen,
        "build_generator": build_generator,
        "build_site": build_site,
        "all": lambda: [clean_gen(), build_site(), create_remote_pr()]
    }
    if len(sys.argv) == 1:
        print("Please provide a command")
        print("Available commands: " + ", ".join(commands.keys()))
        return
    for arg in sys.argv[1:]:
        commands[arg]()


if __name__ == '__main__':
    cli()