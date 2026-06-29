# frozen_string_literal: true

# Generates a whimsical name for a freshly-formed party, e.g. "The Doomed
# Fellowship". Stateless; takes an rng so names are reproducible in tests.
module PartyNamer
  ADJECTIVES = %w[Brave Bold Doomed Hapless Gallant Reckless Cursed Merry Grim
                  Lucky Restless Ragtag Fearless Ill-Fated].freeze
  NOUNS = %w[Fellowship Company Band Crew Posse Brigade Coterie Vanguard
             Wanderers Misfits Few Delvers].freeze

  def self.generate(rng = Random.new)
    "The #{ADJECTIVES.sample(random: rng)} #{NOUNS.sample(random: rng)}"
  end
end
