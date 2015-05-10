;(function(app) {

  function buildQuery(scope) {
    var query = {
      offset: (scope.currentPage - 1) * scope.itemsPerPage,
      limit: scope.itemsPerPage
    };

    if (scope.searchTerm !== void(0) && scope.searchTerm.length > 0) {
      query.searchTerm = scope.searchTerm;
    }

    if (scope.searchScope !== void(0) && scope.searchScope.length > 0) {
      query.searchScope = scope.searchScope;
    }

    if (scope.categoryPath !== void(0)) {
      query.categoryPath = scope.categoryPath;
    }

    if (scope.currentCategory !== void(0)) {
      query.currentIndex = scope.currentCategory.index;
    }

    _.chain(scope.filters).keys().each(function(f) {
      var s = scope[f + 'Scope'];
      if (s !== void(0) && s.length > 0) {
        query[f] = s;
      }
    });

    return query;
  }

  function formatDataset(set) {
    return _.extend(set, {
      startDate: moment(set.startDate).format('YYYY-MM-DD'),
      releaseDate: moment(set.releaseDate).format('YYYY-MM-DD')
    });
  }

  app.factory('DataSetsFactory', [
      'TimeoutRestangular',
      'MarketplaceRestangular',
      function(
        TimeoutRestangular,
        MarketplaceRestangular) {
        return {
          getDataSet: function(index, id) {
            return MarketplaceRestangular
            .one('indices', index)
            .one('datasets', id).get()
            .then(function(results) {
              return formatDataset(results.data);
            });
          },
          updateDataSet: function(dataset) {
            return MarketplaceRestangular
            .one('indices', dataset.index)
            .customPUT(
                MarketplaceRestangular.stripRestangular(dataset),
                'datasets/' + dataset.id);
          },
          createDataSet: function(dataset) {
            // we need to do this because it is an
            // object with a key of 0, not an array.
            dataset.categories = [dataset.categories[0]];

            return MarketplaceRestangular
            .one('indices', dataset.index)
            .post('datasets', _.omit(dataset, 'index'));
          },
          getDataSets: function(scope, abort) {
            return TimeoutRestangular(abort).one('data_sets')
            .get(buildQuery(scope))
            .then(function(datasets) {
              return _.extend(datasets, {
                data: _.map(datasets.data, formatDataset)
              });
            });
          }
        };
      }
  ]);
})(window.bunsen);