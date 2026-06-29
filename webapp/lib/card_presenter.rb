# frozen_string_literal: true

require_relative "upgrade"

# Presentation helpers for the web app only (not part of the shared game
# engine): turn a card into the small bits of HTML that make it look like a
# playing card — an art glyph, colored bait "pips", and stat markup. Kept as
# module functions so both the Sinatra app and the templates can call them.
module CardPresenter
  module_function

  # Emoji used as a stand-in for bait-type art until real icons are added.
  BAIT_EMOJI = { glory: "🏆", riches: "💰", undead: "💀", power: "🔮" }.freeze

  HERO_ART = {
    "hero_cleric" => "⛪", "hero_barbarian" => "🪓",
    "hero_rogue" => "🗡️", "hero_mage" => "🧙"
  }.freeze

  BOSS_ART = {
    "boss_lich" => "☠️", "boss_oni" => "👹",
    "boss_vampire" => "🧛", "boss_medusa" => "🐍",
    "boss_goblin_chieftain" => "👺", "boss_malevolent_spirit" => "👻",
    "boss_kobold_chieftain" => "🐲", "boss_necromancer" => "🧟"
  }.freeze

  def bait_pips(icons)
    icons.to_h.map { |bait, count| pip(bait, count) }.join
  end

  def bait_pip(bait)
    pip(bait, 1)
  end

  # Format a dungeon's bait totals as count + icon only, e.g. "2 🏆, 5 💰".
  def bait_summary(totals)
    return "—" if totals.empty?

    totals.map { |bait, count| "#{count} #{BAIT_EMOJI[bait]}" }.join(", ")
  end

  def pip(bait, count)
    label = count > 1 ? "#{BAIT_EMOJI[bait]}&times;#{count}" : BAIT_EMOJI[bait].to_s
    %(<span class="pip pip-#{bait}" title="#{bait}">#{label}</span>)
  end

  def room_art(room)
    case room.type.downcase
    when /trap/    then "🪤"
    when /monster|creature/ then "👹"
    else "🏰"
    end
  end

  def hero_art(hero)
    HERO_ART.fetch(hero.id, "🧝")
  end

  def boss_art(boss)
    BOSS_ART.fetch(boss.id, "👑")
  end

  # Inner HTML for each card kind (wrapped by the template in a div or button).

  # The damage stat: the effective total next to the sword icon. `parts` is the
  # breakdown (base first, then each positive bonus); their sum is the total.
  def damage_stat(parts)
    %(<span class="dmg">⚔ #{parts.sum}</span>)
  end

  # A small breakdown line ("5 + 2 + 2") shown below the stats when the damage
  # is more than just the base.
  def damage_breakdown(parts)
    shown = parts.reject(&:zero?)
    return "" if shown.size <= 1

    %(<div class="dmg-math">#{shown.join(' + ')}</div>)
  end

  def room_inner(room, parts: nil)
    parts ||= [room.damage]
    <<~HTML
      <div class="card-art">#{room_art(room)}</div>
      <div class="card-title">#{room.name}</div>
      <div class="card-type">#{room.type}</div>
      <div class="card-stats">#{damage_stat(parts)}<span class="baits">#{bait_pips(room.bait)}</span></div>
      #{damage_breakdown(parts)}
      <div class="card-desc">#{room.description}</div>
    HTML
  end

  def boss_inner(boss, parts: nil)
    parts ||= [boss.damage]
    ability = boss.ability_text.empty? ? "" : %(<div class="card-desc">#{boss.ability_text}</div>)
    <<~HTML
      <div class="card-art">#{boss_art(boss)}</div>
      <div class="card-title">#{boss.name}</div>
      <div class="card-type">Boss</div>
      <div class="card-stats">#{damage_stat(parts)}<span class="baits">#{bait_pips(boss.bait)}</span></div>
      #{damage_breakdown(parts)}
      #{ability}
    HTML
  end

  # Inner HTML for a hand card, dispatching on room vs. upgrade.
  def card_inner(card)
    card.is_a?(Upgrade) ? upgrade_inner(card) : room_inner(card)
  end

  def upgrade_inner(upgrade)
    effect = upgrade.bonus_damage.positive? ? %(<span class="dmg">+#{upgrade.bonus_damage} ⚔</span>) : "<span></span>"
    <<~HTML
      <div class="card-art">⬆️</div>
      <div class="card-title">#{upgrade.name}</div>
      <div class="card-type">Upgrade</div>
      <div class="card-stats">#{effect}<span class="baits">#{bait_pips(upgrade.bait)}</span></div>
      <div class="card-desc">#{upgrade.description}</div>
    HTML
  end

  def ability_inner(card)
    <<~HTML
      <div class="card-art">✨</div>
      <div class="card-title">#{card.name}</div>
      <div class="card-type">Ability</div>
      <div class="card-desc">#{card.text}</div>
    HTML
  end

  def hero_inner(hero)
    ability = hero.ability_text.empty? ? "" : %(<div class="card-desc">#{hero.ability_text}</div>)
    <<~HTML
      <div class="card-art">#{hero_art(hero)}</div>
      <div class="card-title">#{hero.name}</div>
      <div class="card-type">Hero</div>
      <div class="card-stats"><span class="hp">❤ #{hero.health}</span><span title="courage">🦁 #{hero.courage}</span><span class="baits">#{bait_pip(hero.preferred_bait)}</span></div>
      #{ability}
    HTML
  end

  # A tiny two-row hero summary: top row art glyph (with ×count when a class is
  # stacked) + preferred-bait pip, bottom row health + courage. Used for the
  # compact town display; tapping it reveals the full hero_inner card.
  def hero_chip(hero, count = 1)
    qty = count > 1 ? %(<span class="hq">×#{count}</span>) : ""
    <<~HTML
      <span class="hc-top"><span class="hico">#{hero_art(hero)}</span>#{qty}#{bait_pip(hero.preferred_bait)}</span>
      <span class="hc-bot"><span class="hp" title="health">❤ #{hero.health}</span><span title="courage">🦁 #{hero.courage}</span></span>
    HTML
  end
end
