(ns hyperopen.funding.test-support.hyperunit)

(def deposit-address-cases
  [{:asset "btc"
    :from-chain "bitcoin"
    :network "Bitcoin"
    :generated-address "bc1qexamplexyz"
    :signature {:r "0x1"}}
   {:asset "eth"
    :from-chain "ethereum"
    :network "Ethereum"
    :generated-address "0xfeedbeef"
    :signature {:r "0x2"}}
   {:asset "sol"
    :from-chain "solana"
    :network "Solana"
    :generated-address "solanaAddressExample"
    :signature {:r "0x3"}}
   {:asset "2z"
    :from-chain "solana"
    :network "Solana"
    :generated-address "zzAddressExample"
    :signature {:r "0x4"}}
   {:asset "bonk"
    :from-chain "solana"
    :network "Solana"
    :generated-address "bonkAddressExample"
    :signature {:r "0x5"}}
   {:asset "ena"
    :from-chain "ethereum"
    :network "Ethereum"
    :generated-address "0xenaAddressExample"
    :signature {:r "0x6"}}
   {:asset "fart"
    :from-chain "solana"
    :network "Solana"
    :generated-address "fartAddressExample"
    :signature {:r "0x7"}}
   {:asset "mon"
    :from-chain "monad"
    :network "Monad"
    :generated-address "monAddressExample"
    :signature {:r "0x8"}}
   {:asset "pump"
    :from-chain "solana"
    :network "Solana"
    :generated-address "pumpAddressExample"
    :signature {:r "0x9"}}
   {:asset "spxs"
    :from-chain "solana"
    :network "Solana"
    :generated-address "spxAddressExample"
    :signature {:r "0xa"}}
   {:asset "xpl"
    :from-chain "plasma"
    :network "Plasma"
    :generated-address "xplAddressExample"
    :signature {:r "0xb"}}])
