def debian_java_deps():
    native.new_local_repository(
        name = "debian_java_deps",
        path = "/usr/share/java",
        build_file = "//scripts/packages/debian/debian_build:debian_java.BUILD",
    )
    
