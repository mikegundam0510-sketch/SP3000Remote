// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "RemoteCore",
    products: [.library(name: "RemoteCore", targets: ["RemoteCore"])],
    targets: [
        .target(name: "RemoteCore"),
        .testTarget(name: "RemoteCoreTests", dependencies: ["RemoteCore"])
    ]
)
