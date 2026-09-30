# Ekosystem — koncepcje współpracy

> 2026-09-30 · Notatka z brainstormu. Możliwe kierunki, nie opis gotowych integracji ani zlecenie ich wdrożenia.

## 1. Wspólny opis ekosystemu

Projekty rozwijamy jako ekosystem wzajemnie użytecznych zdolności, a nie sztywny łańcuch aplikacji o rozłącznych rolach. Każdy może udostępniać innym wiedzę, narzędzia, sposoby interakcji i doświadczenia, a także z nich korzystać. Współpraca nie wymaga rezygnacji z samodzielnej użyteczności projektów.

**Najbardziej bezpośredni kierunek to ChatADHD jako interfejs do AGEDS oraz do części lub całości WatchDoga.** Nie tylko do zadawania pytań o wyniki, ale również do pracy z materiałem i kierowania zadaniami. Dalej można rozważać ChatADHD jako interfejs do pozostałych projektów. Szersza filozofia dopuszcza wszelkie możliwe powiązania interfejsów z danymi i sterowaniem: rozmowę, głos, graf, widok przestrzenny, gesty czy urządzenia. To horyzont koncepcyjny, nie obecny plan implementacji; czat nie musi zastępować innych interfejsów.

**Przepływ jest dwukierunkowy.** iOmatrix nie jest wyłącznie wejściem i wyjściem: może korzystać ze struktur wiedzy i pamięci rozwijanych w ekosystemie, np. do zapisywania nauczonych rzeczy o użytkowniku, jego słowniku, preferencjach, otoczeniu i sposobach działania. Wiedza ta nie musi należeć do interfejsu ChatADHD. Analogicznie inne projekty mogą wzajemnie korzystać ze swoich metod i doświadczeń.

Wspólne obszary do rozważenia to pamięć i kontekst pracy, schowek dla grafów i innych struktur, zaznaczanie nieostre, przechodzenie między reprezentacjami, pamięć korekt i niedokończonych zadań, pochodzenie informacji oraz przenoszenie nauczonych procedur. Obserwacja, wypowiedź użytkownika, hipoteza modelu i wynik działania pozostają rozróżnialne. Współdzielenie nie oznacza automatycznego dostępu do wszystkich danych ani uprawnień do działania.

Mapa obejmuje ChatADHD, iOmatrix (repozytorium Custom-Keyboard-Pro), Loom, AGEDS, WatchDog, program LEM i jego Workbench oraz PixelSpace AR. Otwarta pozostaje także na książkę/meta-książkę, wątki Legal Flow, narzędzia multimedialne i rekonstrukcję scen, agentów i avatar, DevBox i środowiska pracy, analizę archiwów oraz programy badawcze takie jak RCH. Dawny projekt może wrócić jako samodzielny produkt, współdzielona zdolność albo źródło metod; nie oznacza to automatycznego wznowienia wszystkich prac.

Nie rozstrzygamy tutaj jednej aplikacji, bazy, technologii, podziału repozytoriów ani ostatecznego modelu danych. Różne grafy nie muszą mieć tej samej semantyki. Zachowujemy alternatywy i szukamy rzeczywistych korzyści współpracy zamiast łączyć wszystko na siłę. Ta notatka nie zmienia bieżących priorytetów, kontraktów ani kryteriów gotowości funkcji.

## 2. Znaczenie dla iOmatrix

Ta notatka używa aktualnej nazwy iOmatrix dla kierunku rozwijanego w repozytorium Custom-Keyboard-Pro. Klawiatura jest punktem wyjścia, nie granicą koncepcji.

- **Odbiorca i współtwórca wiedzy:** iOmatrix może korzystać ze struktur pamięci rozwijanych w Loom i ChatADHD, aby zapisywać nauczone rzeczy o użytkowniku, jego słowniku, korektach, preferencjach, otoczeniu i sposobach wykonywania zadań. Dostęp do tej zdolności nie musi wymagać uruchamiania czata.
- **Uczenie przenoszone między projektami:** korekta dyktowania, wyjaśnienie intencji w ChatADHD albo doświadczenie z wykonania czynności może pomagać innym narzędziom. Deklaracja użytkownika i przypuszczenie systemu pozostają różnymi rodzajami informacji.
- **Przenośny przedmiot pracy:** schowek dla grafów, zaznaczanie nieostre i przechodzenie między tekstem, strukturą, obrazem czy głosem mogą służyć ChatADHD, AGEDS, WatchDogowi i narzędziom twórczym.
- **Sterowanie i wykonanie:** współpraca z ChatADHD, agentem, DevBox i urządzeniami może łączyć cel opisany w rozmowie z dostępnymi sposobami działania. Pamięć procedur i wyników wykonania może następnie wzbogacać wspólną wiedzę.
- **PixelSpace, multimedia i książka:** wspólne gesty i inne modalności, powiązanie obiektów fizycznych z wiedzą oraz dopasowanie formy wypowiedzi do intencji bez jej niejawnej zmiany. LEM może badać zachowanie sensu przy takich przekształceniach.

Nie sprowadzamy iOmatrix do akcesorium ChatADHD ani do transportu danych. To potencjalnie samodzielny, uczący się uczestnik ekosystemu; uniwersalność interakcji pozostaje horyzontem koncepcyjnym, a nie zleceniem wdrożenia wszystkich połączeń teraz.
