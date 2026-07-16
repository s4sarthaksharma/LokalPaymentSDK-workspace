Pod::Spec.new do |s|
  s.name         = "NativeIapBridge"
  s.version      = "1.0.0"
  s.summary      = "Objective-C-visible bridge over StoreKit 2 for LokalPaymentSDK's :native-iap module."
  s.homepage     = "https://github.com/getlokalapp/LokalPaymentSDK"
  s.license      = { :type => "Proprietary" }
  s.author       = { "Lokal" => "engineering@getlokalapp.com" }
  s.source       = { :path => "." }
  s.ios.deployment_target = "16.0"
  s.swift_version = "5.9"
  s.source_files = "*.swift"
  s.frameworks   = "StoreKit"
end
