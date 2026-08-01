# Test fixture attribution

`alps.json` and `alps.title.xml` are copied unmodified from
[koriym/app-state-diagram](https://github.com/koriym/app-state-diagram)
(`tests/Fake/`), which is distributed under the MIT License,
Copyright (c) 2019-2024 Akihito Koriyama.

They are used here as read-only parser fixtures so that `AlpsNormalizer` is verified against
profiles from the reference ALPS implementation rather than against hand-written samples.
