
package com.boxcinema.admin.service;

import com.boxcinema.common.entity.Genre;

import java.util.List;

public interface GenreManagementService {

    List<Genre> getAllGenres();

    Genre getGenreById(Long genreId);

    Genre createGenre(String genreName, String description);

    Genre updateGenre(Long genreId, String genreName, String description);

    void deleteGenre(Long genreId);
}
