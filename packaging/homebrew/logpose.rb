# Homebrew cask for LogPose for Mac — a TEMPLATE.
#
# The release workflow (.github/workflows/desktop-release.yml) fills in `version` and `sha256`
# for each desktop-v<version> tag and attaches the result to the GitHub Release as `logpose.rb`.
# To publish it, copy that file into a tap:
#
#   github.com/siddharthjaswal/homebrew-logpose  →  Casks/logpose.rb
#   brew install --cask siddharthjaswal/logpose/logpose
#
# homebrew/cask (the main repository, `brew install --cask logpose`) only accepts apps that are
# signed and notarized, so submit there only once releases are built with a Developer ID.
# See RELEASING.md → "Mac app" → "Homebrew".
cask "logpose" do
  version "1.0.0"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"

  url "https://github.com/siddharthjaswal/logpose/releases/download/desktop-v#{version}/LogPose-#{version}-arm64.dmg"
  name "LogPose"
  desc "Read an Android app's network traffic and events, record a test session, share it"
  homepage "https://github.com/siddharthjaswal/logpose"

  livecheck do
    url :url
    regex(/^desktop-v(\d+(?:\.\d+)+)$/i)
    strategy :github_releases
  end

  depends_on arch: :arm64
  depends_on macos: ">= :big_sur"

  app "LogPose.app"

  zap trash: [
    "~/Library/Application Support/LogPose",
    "~/Library/Preferences/io.github.siddharthjaswal.logpose.desktop.plist",
    "~/Library/Saved Application State/io.github.siddharthjaswal.logpose.desktop.savedState",
  ]
end
