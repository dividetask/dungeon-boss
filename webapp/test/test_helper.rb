# frozen_string_literal: true

require "minitest/autorun"

LIB = File.expand_path("../lib", __dir__)
CARDS_PATH = File.expand_path("../../data/cards", __dir__)

# Require every library file so tests can reference the model and phase classes.
Dir[File.join(LIB, "**", "*.rb")].sort.each { |file| require file }
