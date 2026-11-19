import Foundation
import Speech

guard CommandLine.arguments.count > 1 else {
    fputs("Usage: transcribe <wav-path>\n", stderr)
    exit(1)
}

let audioURL = URL(fileURLWithPath: CommandLine.arguments[1])
let sem = DispatchSemaphore(value: 0)
var exitCode: Int32 = 0

SFSpeechRecognizer.requestAuthorization { status in
    guard status == .authorized else {
        fputs("Speech authorization denied (status=\(status.rawValue)). Grant Speech Recognition in System Settings.\n", stderr)
        exitCode = 2
        sem.signal()
        return
    }

    guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US")), recognizer.isAvailable else {
        fputs("Speech recognizer unavailable\n", stderr)
        exitCode = 3
        sem.signal()
        return
    }

    let request = SFSpeechURLRecognitionRequest(url: audioURL)
    if #available(macOS 13.0, *) {
        request.requiresOnDeviceRecognition = true
        request.addsPunctuation = true
    }

    recognizer.recognitionTask(with: request) { result, error in
        if let error = error {
            fputs("STT error: \(error.localizedDescription)\n", stderr)
            exitCode = 4
            sem.signal()
            return
        }
        if let result = result, result.isFinal {
            print(result.bestTranscription.formattedString)
            sem.signal()
        }
    }
}

_ = sem.wait(timeout: .now() + 20)
exit(exitCode)
