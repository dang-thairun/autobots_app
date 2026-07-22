import SwiftUI
import Network

struct PhotoItem: Identifiable {
    let id = UUID()
    let index: Int
    let timestamp: String
    let image: UIImage
    let url: String
}

struct RemoteStateUpdate: Codable {
    let isCapturing: Bool
    let captureMode: String
    let isArmed: Bool
    let lastFired: Bool
    let passageGateOpen: Bool
    let keptPhotoCount: Int
    let lastBurstSaved: Int
    let lastGalleryUri: String?
    let isBursting: Bool
    let thermalLabel: String
    let thermalLevel: Int
    let usedRamMb: Int64
    let totalRamMb: Int64
    let faceCount: Int
    let proximity: Float
    let armThreshold: Float
    let fireThreshold: Float
    let exposureLine: String
    let deviceLoadLine: String
    let lastPhotoUrl: String?
}

struct RemoteControlCommand: Codable {
    let action: String
    let captureMode: String?
    let armThreshold: Float?
    let fireThreshold: Float?
    
    static let ACTION_TOGGLE_CAPTURE = "TOGGLE_CAPTURE"
    static let ACTION_SET_CAPTURE_MODE = "SET_CAPTURE_MODE"
    static let ACTION_SET_ARM_THRESHOLD = "SET_ARM_THRESHOLD"
    static let ACTION_SET_FIRE_THRESHOLD = "SET_FIRE_THRESHOLD"
}

class AutoBotsClient: ObservableObject {
    @Published var isConnected = false
    @Published var errorMessage: String? = nil
    
    // UI states mirrored from Android Server
    @Published var isCapturing = false
    @Published var captureMode = "Standard"
    @Published var isArmed = false
    @Published var lastFired = false
    @Published var passageGateOpen = true
    @Published var keptPhotoCount = 0
    @Published var lastBurstSaved = 0
    @Published var faceCount = 0
    @Published var proximity: Float = 0.0
    @Published var armThreshold: Float = 0.04
    @Published var fireThreshold: Float = 0.10
    @Published var exposureLine = "—mm  ·  —  ·  ISO —"
    @Published var deviceLoadLine = "Load: thermal=—  RAM —"
    
    @Published var livePreviewImage: UIImage? = nil
    @Published var latestPhotos: [PhotoItem] = []
    
    private var controlTask: URLSessionWebSocketTask? = nil
    private var previewTask: URLSessionWebSocketTask? = nil
    private var lastLoadedPhotoUrl: String? = nil
    private var session = URLSession(configuration: .default)
    
    func connect(ip: String) {
        disconnect()
        
        var cleanedIp = ip.trimmingCharacters(in: .whitespacesAndNewlines)
        cleanedIp = cleanedIp.replacingOccurrences(of: "http://", with: "")
                             .replacingOccurrences(of: "https://", with: "")
                             .replacingOccurrences(of: "ws://", with: "")
                             .replacingOccurrences(of: "wss://", with: "")
        
        guard !cleanedIp.isEmpty else {
            self.errorMessage = "Please enter an IP address."
            return
        }
        
        let controlUrl = URL(string: "ws://\(cleanedIp)/ws/control")!
        let previewUrl = URL(string: "ws://\(cleanedIp)/ws/preview")!
        
        // Connect Control Socket
        controlTask = session.webSocketTask(with: controlUrl)
        controlTask?.resume()
        
        // Connect Preview Socket
        previewTask = session.webSocketTask(with: previewUrl)
        previewTask?.resume()
        
        self.isConnected = true
        self.errorMessage = nil
        
        listenControl()
        listenPreview()
    }
    
    func disconnect() {
        controlTask?.cancel(with: .goingAway, reason: nil)
        previewTask?.cancel(with: .goingAway, reason: nil)
        controlTask = nil
        previewTask = nil
        DispatchQueue.main.async {
            self.isConnected = false
            self.livePreviewImage = nil
        }
    }
    
    func sendCommand(_ cmd: RemoteControlCommand) {
        guard let task = controlTask, isConnected else { return }
        do {
            let data = try JSONEncoder().encode(cmd)
            if let jsonString = String(data: data, encoding: .utf8) {
                task.send(.string(jsonString)) { error in
                    if let error = error {
                        print("Send command failed: \(error)")
                    }
                }
            }
        } catch {
            print("Encoding command failed: \(error)")
        }
    }
    
    private func listenControl() {
        guard let currentTask = controlTask else { return }
        currentTask.receive { [weak self] result in
            guard let self = self else { return }
            guard self.controlTask === currentTask else { return }
            
            switch result {
            case .failure(let error):
                let nsError = error as NSError
                if nsError.code == -999 { // NSURLErrorCancelled
                    return
                }
                DispatchQueue.main.async {
                    self.errorMessage = "Control Connection Lost: \(error.localizedDescription)"
                    self.isConnected = false
                }
            case .success(let message):
                switch message {
                case .string(let text):
                    self.parseStateUpdate(text)
                default:
                    break
                }
                self.listenControl()
            }
        }
    }
    
    private func listenPreview() {
        guard let currentTask = previewTask else { return }
        currentTask.receive { [weak self] result in
            guard let self = self else { return }
            guard self.previewTask === currentTask else { return }
            
            switch result {
            case .failure(let error):
                let nsError = error as NSError
                if nsError.code == -999 {
                    return
                }
                print("Preview socket error: \(error)")
            case .success(let message):
                switch message {
                case .data(let data):
                    DispatchQueue.main.async {
                        if let rawImage = UIImage(data: data), let cgImage = rawImage.cgImage {
                            // Rotate 90 degrees clockwise (right)
                            self.livePreviewImage = UIImage(cgImage: cgImage, scale: 1.0, orientation: .right)
                        }
                    }
                default:
                    break
                }
                self.listenPreview()
            }
        }
    }
    
    private func parseStateUpdate(_ jsonText: String) {
        guard let data = jsonText.data(using: .utf8) else { return }
        do {
            let update = try JSONDecoder().decode(RemoteStateUpdate.self, from: data)
            DispatchQueue.main.async {
                self.isCapturing = update.isCapturing
                self.captureMode = update.captureMode
                self.isArmed = update.isArmed
                self.lastFired = update.lastFired
                self.passageGateOpen = update.passageGateOpen
                self.keptPhotoCount = update.keptPhotoCount
                self.lastBurstSaved = update.lastBurstSaved
                self.faceCount = update.faceCount
                self.proximity = update.proximity
                self.armThreshold = update.armThreshold
                self.fireThreshold = update.fireThreshold
                self.exposureLine = update.exposureLine
                self.deviceLoadLine = update.deviceLoadLine
                
                if let photoUrl = update.lastPhotoUrl, photoUrl != self.lastLoadedPhotoUrl {
                    self.lastLoadedPhotoUrl = photoUrl
                    self.downloadPhoto(photoUrl)
                }
            }
        } catch {
            print("Failed to parse state JSON: \(error)")
        }
    }
    
    private func downloadPhoto(_ urlString: String) {
        guard let url = URL(string: urlString) else { return }
        session.dataTask(with: url) { [weak self] data, _, error in
            guard let self = self, let data = data, let image = UIImage(data: data) else { return }
            
            let formatter = DateFormatter()
            formatter.dateFormat = "HH:mm:ss"
            let timeStr = formatter.string(from: Date())
            
            DispatchQueue.main.async {
                let index = self.latestPhotos.count + 1
                let item = PhotoItem(index: index, timestamp: timeStr, image: image, url: urlString)
                // Insert at 0 (top-left)
                self.latestPhotos.insert(item, at: 0)
            }
        }.resume()
    }
}

struct ContentView: View {
    @StateObject private var client = AutoBotsClient()
    @State private var ipAddress = "192.168.1.129:8080"
    @State private var selectedPhoto: PhotoItem? = nil
    @State private var activePhotoId = UUID()
    
    private let columns = [
        GridItem(.flexible(), spacing: 10),
        GridItem(.flexible(), spacing: 10)
    ]
    
    var body: some View {
        VStack(spacing: 0) {
            // Connection Top Bar
            HStack {
                Text("AutoBots Remote Monitor")
                    .font(.headline)
                    .foregroundColor(.white)
                
                Spacer()
                
                if client.isConnected {
                    HStack(spacing: 8) {
                        Circle()
                            .fill(Color.green)
                            .frame(width: 8, height: 8)
                        Text("Connected to \(ipAddress)")
                            .foregroundColor(.green)
                            .font(.subheadline)
                        
                        Button("Disconnect") {
                            client.disconnect()
                        }
                        .foregroundColor(.red)
                        .buttonStyle(.bordered)
                    }
                } else {
                    HStack(spacing: 12) {
                        Text("Android IP:")
                            .foregroundColor(.gray)
                        TextField("e.g. 192.168.1.15:8080", text: $ipAddress)
                            .textFieldStyle(RoundedBorderTextFieldStyle())
                            .frame(width: 200)
                            .keyboardType(.numbersAndPunctuation)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                        
                        Button("Connect") {
                            client.connect(ip: ipAddress)
                        }
                        .tint(.blue)
                        .foregroundColor(.white)
                        .buttonStyle(.borderedProminent)
                    }
                }
            }
            .padding()
            .background(Color(red: 0.1, green: 0.1, blue: 0.12))
            
            if let error = client.errorMessage {
                Text(error)
                    .foregroundColor(.white)
                    .padding(8)
                    .frame(maxWidth: .infinity)
                    .background(Color.red)
            }
            
            // Main 3-Section Panel
            HStack(spacing: 0) {
                // SECTION 1: Camera Live Preview (Left)
                VStack(spacing: 8) {
                    Text("LIVE PREVIEW")
                        .font(.caption)
                        .bold()
                        .foregroundColor(.gray)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    
                    ZStack {
                        Color.black
                            .cornerRadius(12)
                        
                        if let preview = client.livePreviewImage {
                            Image(uiImage: preview)
                                .resizable()
                                .aspectRatio(contentMode: .fit)
                                .cornerRadius(12)
                        } else {
                            VStack(spacing: 12) {
                                Image(systemName: "camera.fill")
                                    .font(.system(size: 40))
                                    .foregroundColor(.gray)
                                Text("No Live Stream")
                                    .font(.subheadline)
                                    .foregroundColor(.gray)
                            }
                        }
                    }
                }
                .padding()
                .frame(width: UIScreen.main.bounds.width * 0.35)
                
                Divider()
                    .background(Color.gray.opacity(0.3))
                
                // SECTION 2: Latest Photos 2-Column Grid (Middle)
                VStack(spacing: 8) {
                    Text("LATEST PHOTOS")
                        .font(.caption)
                        .bold()
                        .foregroundColor(.gray)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    
                    if client.latestPhotos.isEmpty {
                        VStack {
                            Spacer()
                            Image(systemName: "photo.on.rectangle")
                                .font(.system(size: 40))
                                .foregroundColor(.gray)
                            Text("No Photos Yet")
                                .foregroundColor(.gray)
                                .font(.subheadline)
                                .padding(.top, 8)
                            Spacer()
                        }
                        .frame(maxWidth: .infinity)
                    } else {
                        ScrollView {
                            LazyVGrid(columns: columns, spacing: 10) {
                                ForEach(client.latestPhotos) { photo in
                                    ZStack(alignment: .topTrailing) {
                                        Image(uiImage: photo.image)
                                            .resizable()
                                            .aspectRatio(contentMode: .fill)
                                            .frame(height: 120)
                                            .clipped()
                                            .cornerRadius(8)
                                            .onTapGesture {
                                                activePhotoId = photo.id
                                                selectedPhoto = photo
                                            }
                                        
                                        // Overlay tag top-right corner of image
                                        Text("Photo \(photo.index)  ·  \(photo.timestamp)")
                                            .font(.system(size: 10, weight: .bold))
                                            .foregroundColor(.white)
                                            .padding(.horizontal, 6)
                                            .padding(.vertical, 3)
                                            .background(Color.black.opacity(0.65))
                                            .cornerRadius(4)
                                            .padding([.top, .trailing], 6)
                                    }
                                }
                            }
                            .padding(.top, 4)
                        }
                    }
                }
                .padding()
                .frame(width: UIScreen.main.bounds.width * 0.35)
                
                Divider()
                    .background(Color.gray.opacity(0.3))
                
                // SECTION 3: Camera Control (Right)
                VStack(spacing: 16) {
                    Text("CAMERA STATUS & CONTROL")
                        .font(.caption)
                        .bold()
                        .foregroundColor(.gray)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    
                    // Status block
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Status:")
                                .foregroundColor(.gray)
                            Text(client.isArmed ? "Armed" : "Idle")
                                .bold()
                                .foregroundColor(client.isArmed ? .green : .white)
                        }
                        
                        HStack {
                            Text("Proximity:")
                                .foregroundColor(.gray)
                            Text("\(Int(client.proximity * 100))%")
                                .bold()
                                .foregroundColor(.white)
                        }
                        
                        HStack {
                            Text("Exposure:")
                                .foregroundColor(.gray)
                            Text(client.exposureLine)
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundColor(.white)
                        }
                        
                        HStack {
                            Text("Device Load:")
                                .foregroundColor(.gray)
                            Text(client.deviceLoadLine)
                                .font(.system(size: 12))
                                .foregroundColor(.white)
                        }
                    }
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.gray.opacity(0.15))
                    .cornerRadius(10)
                    
                    // Remote actions
                    VStack(spacing: 12) {
                        Button(action: {
                            client.sendCommand(RemoteControlCommand(action: "TOGGLE_CAPTURE", captureMode: nil, armThreshold: nil, fireThreshold: nil))
                        }) {
                            Text(client.isCapturing ? "STOP CAPTURE" : "START CAPTURE")
                                .bold()
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(client.isCapturing ? Color.red : Color.green)
                                .foregroundColor(.white)
                                .cornerRadius(8)
                        }
                        .disabled(!client.isConnected)
                        
                        HStack {
                            Text("Mode:")
                                .foregroundColor(.gray)
                            Spacer()
                            Picker("Capture Mode", selection: Binding(
                                get: { client.captureMode },
                                set: { newMode in
                                    client.sendCommand(RemoteControlCommand(action: "SET_CAPTURE_MODE", captureMode: newMode, armThreshold: nil, fireThreshold: nil))
                                }
                            )) {
                                Text("Standard").tag("Standard")
                                Text("Max-Sensor").tag("MaxSensor")
                            }
                            .pickerStyle(SegmentedPickerStyle())
                            .frame(width: 180)
                            .disabled(!client.isConnected)
                        }
                    }
                    .padding(.vertical, 8)
                    
                    Divider()
                    
                    // Threshold sliders
                    VStack(alignment: .leading, spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text("Arm (Face Lock):")
                                    .foregroundColor(.gray)
                                Spacer()
                                Text("\(Int(client.armThreshold * 100))%")
                                    .foregroundColor(.white)
                                    .bold()
                            }
                            Slider(value: Binding(
                                get: { client.armThreshold },
                                set: { val in
                                    client.sendCommand(RemoteControlCommand(action: "SET_ARM_THRESHOLD", captureMode: nil, armThreshold: val, fireThreshold: nil))
                                }
                            ), in: 0.01...0.25, step: 0.01)
                            .disabled(!client.isConnected)
                        }
                        
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text("Fire (Burst):")
                                    .foregroundColor(.gray)
                                Spacer()
                                Text("\(Int(client.fireThreshold * 100))%")
                                    .foregroundColor(.white)
                                    .bold()
                            }
                            Slider(value: Binding(
                                get: { client.fireThreshold },
                                set: { val in
                                    client.sendCommand(RemoteControlCommand(action: "SET_FIRE_THRESHOLD", captureMode: nil, armThreshold: nil, fireThreshold: val))
                                }
                            ), in: 0.02...0.45, step: 0.01)
                            .disabled(!client.isConnected)
                        }
                    }
                    
                    Spacer()
                }
                .padding()
                .frame(maxWidth: .infinity)
            }
            .background(Color(red: 0.15, green: 0.15, blue: 0.18))
        }
        .edgesIgnoringSafeArea(.bottom)
        .preferredColorScheme(.dark)
        // Full-screen lightbox for photo inspect
        .fullScreenCover(item: $selectedPhoto) { photo in
            ZStack {
                Color.black.edgesIgnoringSafeArea(.all)
                
                TabView(selection: $activePhotoId) {
                    ForEach(client.latestPhotos) { item in
                        Image(uiImage: item.image)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .tag(item.id)
                            .edgesIgnoringSafeArea(.all)
                    }
                }
                .tabViewStyle(PageTabViewStyle(indexDisplayMode: .never))
                .edgesIgnoringSafeArea(.all)
                
                VStack {
                    HStack {
                        Spacer()
                        Button(action: {
                            selectedPhoto = nil
                        }) {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 32))
                                .foregroundColor(.white.opacity(0.8))
                                .padding(24)
                        }
                    }
                    Spacer()
                }
            }
        }
        .onAppear {
            triggerLocalNetworkPrompt()
        }
    }
    
    private func triggerLocalNetworkPrompt() {
        let connection = NWConnection(host: "224.0.0.251", port: 80, using: .udp)
        connection.stateUpdateHandler = { _ in }
        connection.start(queue: .main)
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            connection.cancel()
        }
    }
}
