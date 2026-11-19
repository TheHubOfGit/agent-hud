// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "AgentHUD",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "AgentHUD",
            path: "Sources/AgentHUD",
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: [
                .linkedFramework("AppKit"),
                .linkedFramework("SwiftUI"),
                .linkedFramework("ServiceManagement"),
            ]
        )
    ]
)
