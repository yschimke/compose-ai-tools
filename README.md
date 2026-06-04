# adaptive-apps-samples (AdaptiveJetStream — XR spatial Compose) — Compose previews

Auto-rendered by the integration matrix from [`android/adaptive-apps-samples@main`](https://github.com/android/adaptive-apps-samples/tree/main). Updated on every push to `main`.

## CI notes

- XR spatial Compose. Upstream tracks `androidx.xr.compose`
  alpha07, whose `ApplicationSubspace` / `MovePolicy` /
  `SpatialConfiguration` APIs were removed or deprecated by
  alpha14 — the version our XR render path compiles against. The
  integration run therefore applies the in-repo
  `adaptive-apps-samples-xr-alpha14.patch` before configuring the
  build.
- 12 device-targeted previews via custom multi-preview
  annotations (`@PhonePreview` / `@TvPreview` / …).


### Workarounds applied by the integration harness

- Source: [`android/adaptive-apps-samples@main`](https://github.com/android/adaptive-apps-samples/tree/main)
- Consumer patch applied before configuring the build: `adaptive-apps-samples-xr-alpha14.patch` (idempotent — auto-skipped once the change lands upstream).

## jetstream

| Preview | Image |
|---------|-------|
| `BackButtonScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/BackButtonScreenshot.png" width="150" /> |
| `ErrorScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ErrorScreenshot.png" width="150" /> |
| `LoadingScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LoadingScreenshot.png" width="150" /> |
| `MovieCardScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieCardScreenshot.png" width="150" /> |
| `RequestFullSpaceModeItemPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/RequestFullSpaceModeItemPreview.png" width="150" /> |
| `TopAppBarPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/TopAppBarPreview.png" width="150" /> |
| `UserAvatarScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/UserAvatarScreenshot.png" width="150" /> |
| `WatchNowButtonScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/WatchNowButtonScreenshot.png" width="150" /> |
| `CategoriesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenScreenshot_Auto.png" width="150" /> |
| `CategoriesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenScreenshot_Desktop.png" width="150" /> |
| `CategoriesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenScreenshot_Foldable.png" width="150" /> |
| `CategoriesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenScreenshot_Phone.png" width="150" /> |
| `CategoriesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenScreenshot_TV.png" width="150" /> |
| `CategoriesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenScreenshot_Tablet.png" width="150" /> |
| `CategoryMovieListScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoryMovieListScreenScreenshot_Auto.png" width="150" /> |
| `CategoryMovieListScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoryMovieListScreenScreenshot_Desktop.png" width="150" /> |
| `CategoryMovieListScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoryMovieListScreenScreenshot_Foldable.png" width="150" /> |
| `CategoryMovieListScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoryMovieListScreenScreenshot_Phone.png" width="150" /> |
| `CategoryMovieListScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoryMovieListScreenScreenshot_TV.png" width="150" /> |
| `CategoryMovieListScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoryMovieListScreenScreenshot_Tablet.png" width="150" /> |
| `FavouritesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/FavouritesScreenScreenshot_Auto.png" width="150" /> |
| `FavouritesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/FavouritesScreenScreenshot_Desktop.png" width="150" /> |
| `FavouritesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/FavouritesScreenScreenshot_Foldable.png" width="150" /> |
| `FavouritesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/FavouritesScreenScreenshot_Phone.png" width="150" /> |
| `FavouritesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/FavouritesScreenScreenshot_TV.png" width="150" /> |
| `FavouritesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/FavouritesScreenScreenshot_Tablet.png" width="150" /> |
| `HomeScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HomeScreenScreenshot_Auto.png" width="150" /> |
| `HomeScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HomeScreenScreenshot_Desktop.png" width="150" /> |
| `HomeScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HomeScreenScreenshot_Foldable.png" width="150" /> |
| `HomeScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HomeScreenScreenshot_Phone.png" width="150" /> |
| `HomeScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HomeScreenScreenshot_TV.png" width="150" /> |
| `HomeScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HomeScreenScreenshot_Tablet.png" width="150" /> |
| `MovieDetailsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieDetailsScreenScreenshot_Auto.png" width="150" /> |
| `MovieDetailsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieDetailsScreenScreenshot_Desktop.png" width="150" /> |
| `MovieDetailsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieDetailsScreenScreenshot_Foldable.png" width="150" /> |
| `MovieDetailsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieDetailsScreenScreenshot_Phone.png" width="150" /> |
| `MovieDetailsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieDetailsScreenScreenshot_TV.png" width="150" /> |
| `MovieDetailsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieDetailsScreenScreenshot_Tablet.png" width="150" /> |
| `MoviesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MoviesScreenScreenshot_Auto.png" width="150" /> |
| `MoviesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MoviesScreenScreenshot_Desktop.png" width="150" /> |
| `MoviesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MoviesScreenScreenshot_Foldable.png" width="150" /> |
| `MoviesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MoviesScreenScreenshot_Phone.png" width="150" /> |
| `MoviesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MoviesScreenScreenshot_TV.png" width="150" /> |
| `MoviesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MoviesScreenScreenshot_Tablet.png" width="150" /> |
| `NavigationSuiteScaffoldLayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/NavigationSuiteScaffoldLayoutPreview_Foldable.png" width="150" /> |
| `NavigationSuiteScaffoldLayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/NavigationSuiteScaffoldLayoutPreview_Phone.png" width="150" /> |
| `NavigationSuiteScaffoldLayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/NavigationSuiteScaffoldLayoutPreview_Tablet.png" width="150" /> |
| `SearchScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchScreenScreenshot_Auto.png" width="150" /> |
| `SearchScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchScreenScreenshot_Desktop.png" width="150" /> |
| `SearchScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchScreenScreenshot_Foldable.png" width="150" /> |
| `SearchScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchScreenScreenshot_Phone.png" width="150" /> |
| `SearchScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchScreenScreenshot_TV.png" width="150" /> |
| `SearchScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchScreenScreenshot_Tablet.png" width="150" /> |
| `ShowsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ShowsScreenScreenshot_Auto.png" width="150" /> |
| `ShowsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ShowsScreenScreenshot_Desktop.png" width="150" /> |
| `ShowsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ShowsScreenScreenshot_Foldable.png" width="150" /> |
| `ShowsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ShowsScreenScreenshot_Phone.png" width="150" /> |
| `ShowsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ShowsScreenScreenshot_TV.png" width="150" /> |
| `ShowsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ShowsScreenScreenshot_Tablet.png" width="150" /> |
| `TopBarWithNavigationLayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/TopBarWithNavigationLayoutPreview_Auto.png" width="150" /> |
| `TopBarWithNavigationLayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/TopBarWithNavigationLayoutPreview_Desktop.png" width="150" /> |
| `TopBarWithNavigationLayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/TopBarWithNavigationLayoutPreview_TV.png" width="150" /> |
| `CategoriesScreenFoldablePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenFoldablePreview_Foldable.png" width="150" /> |
| `CategoriesScreenPhonePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenPhonePreview_Phone.png" width="150" /> |
| `CategoriesScreenTvPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenTvPreview_TV.png" width="150" /> |
| `ProfileScreenFoldablePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ProfileScreenFoldablePreview_Foldable.png" width="150" /> |
| `ProfileScreenPhonePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ProfileScreenPhonePreview_Phone.png" width="150" /> |
| `ProfileScreenTvPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ProfileScreenTvPreview_TV.png" width="150" /> |
| `AboutSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionScreenshot_Auto.png" width="150" /> |
| `AboutSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionScreenshot_Desktop.png" width="150" /> |
| `AboutSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionScreenshot_Foldable.png" width="150" /> |
| `AboutSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionScreenshot_Phone.png" width="150" /> |
| `AboutSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionScreenshot_TV.png" width="150" /> |
| `AboutSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionScreenshot_Tablet.png" width="150" /> |
| `AccountsSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountsSectionScreenshot_Auto.png" width="150" /> |
| `AccountsSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountsSectionScreenshot_Desktop.png" width="150" /> |
| `AccountsSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountsSectionScreenshot_Foldable.png" width="150" /> |
| `AccountsSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountsSectionScreenshot_Phone.png" width="150" /> |
| `AccountsSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountsSectionScreenshot_TV.png" width="150" /> |
| `AccountsSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountsSectionScreenshot_Tablet.png" width="150" /> |
| `HelpAndSupportSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpAndSupportSectionScreenshot_Auto.png" width="150" /> |
| `HelpAndSupportSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpAndSupportSectionScreenshot_Desktop.png" width="150" /> |
| `HelpAndSupportSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpAndSupportSectionScreenshot_Foldable.png" width="150" /> |
| `HelpAndSupportSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpAndSupportSectionScreenshot_Phone.png" width="150" /> |
| `HelpAndSupportSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpAndSupportSectionScreenshot_TV.png" width="150" /> |
| `HelpAndSupportSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpAndSupportSectionScreenshot_Tablet.png" width="150" /> |
| `LanguageSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageSectionScreenshot_Auto.png" width="150" /> |
| `LanguageSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageSectionScreenshot_Desktop.png" width="150" /> |
| `LanguageSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageSectionScreenshot_Foldable.png" width="150" /> |
| `LanguageSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageSectionScreenshot_Phone.png" width="150" /> |
| `LanguageSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageSectionScreenshot_TV.png" width="150" /> |
| `LanguageSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageSectionScreenshot_Tablet.png" width="150" /> |
| `SearchHistorySectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionScreenshot_Auto.png" width="150" /> |
| `SearchHistorySectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionScreenshot_Desktop.png" width="150" /> |
| `SearchHistorySectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionScreenshot_Foldable.png" width="150" /> |
| `SearchHistorySectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionScreenshot_Phone.png" width="150" /> |
| `SearchHistorySectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionScreenshot_TV.png" width="150" /> |
| `SearchHistorySectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionScreenshot_Tablet.png" width="150" /> |
| `SubtitlesSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionScreenshot_Auto.png" width="150" /> |
| `SubtitlesSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionScreenshot_Desktop.png" width="150" /> |
| `SubtitlesSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionScreenshot_Foldable.png" width="150" /> |
| `SubtitlesSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionScreenshot_Phone.png" width="150" /> |
| `SubtitlesSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionScreenshot_TV.png" width="150" /> |
| `SubtitlesSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionScreenshot_Tablet.png" width="150" /> |
| `AboutSectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionCompactPreview_Foldable.png" width="150" /> |
| `AboutSectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionCompactPreview_Phone.png" width="150" /> |
| `AboutSectionExpandedPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionExpandedPreview_TV.png" width="150" /> |
| `SingleColumnAccountPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SingleColumnAccountPreview_Foldable.png" width="150" /> |
| `SingleColumnAccountPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SingleColumnAccountPreview_Phone.png" width="150" /> |
| `AccountSelectionItemPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountSelectionItemPreview_Foldable.png" width="150" /> |
| `AccountSelectionItemPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountSelectionItemPreview_Phone.png" width="150" /> |
| `AccountSelectionItemTvPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountSelectionItemTvPreview_TV.png" width="150" /> |
| `HelpSectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpSectionCompactPreview_Foldable.png" width="150" /> |
| `HelpSectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpSectionCompactPreview_Phone.png" width="150" /> |
| `HelpSectionExpandedPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpSectionExpandedPreview_TV.png" width="150" /> |
| `LanguageScreenCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageScreenCompactPreview_Foldable.png" width="150" /> |
| `LanguageScreenCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageScreenCompactPreview_Phone.png" width="150" /> |
| `LanguageScreenExpandedPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageScreenExpandedPreview_TV.png" width="150" /> |
| `SearchHistorySectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionCompactPreview_Foldable.png" width="150" /> |
| `SearchHistorySectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionCompactPreview_Phone.png" width="150" /> |
| `SearchHistorySectionExpandedPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionExpandedPreview_TV.png" width="150" /> |
| `SubtitlesSectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionCompactPreview_Foldable.png" width="150" /> |
| `SubtitlesSectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionCompactPreview_Phone.png" width="150" /> |
| `SubtitlesSectionExpandedPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionExpandedPreview_TV.png" width="150" /> |
| `MediaPlayerMainFramePreviewLayoutOnPhone` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MediaPlayerMainFramePreviewLayoutOnPhone_Phone.png" width="150" /> |
| `MediaPlayerMainFramePreviewLayoutWithoutMore` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MediaPlayerMainFramePreviewLayoutWithoutMore_tv_4k.png" width="150" /> |
| `MediaPlayerMainFramePreviewLayout` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MediaPlayerMainFramePreviewLayout_tv_4k.png" width="150" /> |
| `VideoPlayerMediaTitlePreviewAd` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerMediaTitlePreviewAd_Ads.png" width="150" /> |
| `VideoPlayerMediaTitlePreviewLive` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerMediaTitlePreviewLive_Live.png" width="150" /> |
| `VideoPlayerMediaTitlePreviewSeries` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerMediaTitlePreviewSeries_TV_Series.png" width="150" /> |
| `VideoPlayerMediaTitlePreviewAdScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerMediaTitlePreviewAdScreenshot_Ads.png" width="150" /> |
| `VideoPlayerMediaTitlePreviewLiveScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerMediaTitlePreviewLiveScreenshot_Live.png" width="150" /> |
| `VideoPlayerMediaTitlePreviewSeriesScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerMediaTitlePreviewSeriesScreenshot_TV_Series.png" width="150" /> |
| `VideoPlayerOverlayPreviewForPhone` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerOverlayPreviewForPhone_Phone.png" width="150" /> |
| `VideoPlayerOverlayPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerOverlayPreview_tv_4k.png" width="150" /> |

